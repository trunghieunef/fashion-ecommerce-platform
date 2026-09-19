# 13 — Vận hành, bảo mật và go-live

B1 · 2026-09-19 · Owner DEVOPS + TL, phối hợp OPS/FINANCE/PO. Đây là runbook thiết kế; lệnh/env/dashboard URL cụ thể phải được điền và diễn tập trong OPS-01 trước production.

## 1. Ranh giới tin cậy và quyền

| Boundary / rủi ro | Control bắt buộc | Proof |
|---|---|---|
| Browser → Gateway: giả danh header | Strip client identity headers; JWT/session verify, CORS/CSRF theo cookie | U03/U24 |
| Gateway → service: bypass gateway | Private network/caller identity + service authorization/ownership | Internal public bị chặn; token sai audience fail |
| Guest cart/order: IDOR | Credential ngẫu nhiên đủ mạnh, hash server, cookie secure; scoped resource | T21 |
| Service → DB: đọc chéo | Credentials riêng, least privilege, migrations role tách runtime | Runtime không đọc DB khác/không DDL |
| Provider → callback: giả/trễ/lặp | Signature/auth theo adapter; reference/merchant/amount/currency; dedupe và TX trước ACK | U16/T06 |
| Admin → tiền/kho | Permission riêng, auth_version hiện hành, reason/evidence/audit | U03/U22 |
| Upload/rich text | Type thực/size/ownership, sanitize HTML, controlled public URL | U24 |
| Logs/queue/storage: PII/secret leak | Redaction, scoped access, retention, encrypted transport/storage theo platform | Scan fixtures/logs trong QA |

Không đưa role/user_id/amount từ public input thành authority. Account linking yêu cầu proof của cả tài khoản hiện hữu và provider identity. Không lưu refresh token trong localStorage.

## 2. Ma trận quyền vận hành

| Hành động | OPS | FINANCE | MARKETING | SUPER_ADMIN | DEVOPS |
|---|---|---|---|---|---|
| Catalog/stock/packing/SELF/return evidence | Có | Không | Không | Chỉ khi cấp permission tương ứng | Không bằng quyền hạ tầng |
| Refund/COD settlement/reconcile | Không; chỉ yêu cầu hủy order | Có | Không | Chỉ khi cấp permission tương ứng | Không |
| Voucher/campaign/template/marketing | Không | Không | Có | Theo permission | Không |
| User role/lock | Không | Không | Không | Có | Không |
| Deploy/backup/replay hạ tầng | Không | Phối hợp đối soát | Không | Không mặc nhiên | Có theo release/incident process |

Một người có thể nhiều role nhưng audit phải ghi actor/action/reason thực. Break-glass access cần incident ID, thời hạn, reviewer và audit; không tạo admin password mặc định dùng chung.

## 3. Dữ liệu, consent và retention

| Loại | Nơi sở hữu | Truy cập / bảo vệ | Retention baseline / việc còn mở |
|---|---|---|---|
| Password hash, refresh/reset hash | user-db | Auth runtime, không trả API/admin/log | Token cleanup theo expiry; O06 chốt audit cần giữ |
| OTP/reset secret tạm | Redis mã hóa TTL, user API có scope | notification duy nhất nhận secret tạm; no-store | OTP 5m, reset 30m; xóa sau ACK/hết hạn |
| Profile/address/contact snapshot | user/order/shipping DB tương ứng | Owner và nhân viên có nhu cầu; hạn chế FINANCE xem địa chỉ | O06 chốt thời hạn từng loại, quyền xóa/ẩn danh |
| Payment/refund/settlement/audit | payment/order DB | FINANCE/TL scoped support, payload redacted | Không tự purge trước khi policy O06 được chốt |
| Notification recipient/rendered content | notification-db | Sender/support có scope; OTP dùng marker | O06 chốt TTL ngắn, cleanup verified trước launch |
| Marketing preferences | notification-db | Opt-in, consent timestamp và unsubscribe hash | O06 chốt bằng chứng consent/retention |
| Outbox/inbox | DB service | Application/ops bounded replay | Dự kiến SENT 30 ngày, dedupe 90 ngày; phải đủ replay window |
| Logs/traces | Observability storage | RBAC, mask phone/email/token, không body webhook secret | Logs dự kiến 30 ngày; traces theo budget và O06 |
| Media | Object storage | Scoped upload, public chỉ approved assets | Dọn orphan sau policy; không xóa ảnh evidence tùy ý |

Các mốc kỹ thuật không thay thời hạn pháp lý. PO/phụ trách pháp lý xác minh nghĩa vụ hiện hành trước G2, ghi nguồn và ngày vào O06. Request xem/xóa dữ liệu phải xác minh actor, kiểm tra nghĩa vụ lưu và xử lý từng DB owner; không cascade xóa order/payment lịch sử vì user yêu cầu xóa account.

Secret inventory gồm JWT signing keys, service credentials, DB/Kafka/Redis credentials, merchant secrets, mail/SMS keys. Mỗi secret có owner, env, rotation plan; không lưu trong Nacos plaintext, image, Git hoặc ticket.

## 4. Monitoring và escalation

| Signal | Ngưỡng khởi điểm cần hiệu chỉnh | Owner / phản ứng |
|---|---|---|
| Public 5xx | > 1% trong 5 phút | On-call kiểm tra theo service/route |
| Catalog p95 / checkout acceptance | Vượt 300ms / 2s trong 10 phút | TL/DEVOPS kiểm tra DB/pool/cache, không tăng replica vô hạn |
| Oldest outbox pending | > 60s warning; > 5m critical | DEVOPS + BE kiểm tra Kafka/relay, budget pause checkout |
| Saga overdue hoặc stuck UNKNOWN | Quá deadline + 2 phút | BE/OPS; payment/refund gọi FINANCE |
| Refund UNKNOWN/pending | > 15 phút warning khởi điểm, escalation theo provider SLA | FINANCE đối soát reference |
| Negative stock/over-refund | Bất kỳ vi phạm nào | Sev1, dừng mutation liên quan, bảo toàn evidence |
| COD outstanding | Quá settlement SLA O04 | FINANCE mở discrepancy |
| DLQ tăng hoặc retry không tiến | Bất kỳ event critical; baseline lag > 5.000 cảnh báo thêm | BE kiểm tra poison event/schema, không replay toàn topic ngay |
| Disk/pool saturation | > 80% disk; pool wait tăng | DEVOPS capacity và admission control |
| Auth brute force/role misuse | Theo limiter và denied spike | Security owner/TL, không log credentials |

Dashboard tách business/system errors. HTTP 202 tăng đột biến là tín hiệu cần đo readiness, không xem là hệ thống khỏe chỉ vì không 5xx. Metric labels không chứa ID người dùng/đơn.

On-call roster trước launch phải có primary/backup, kênh liên hệ, giờ trực, SLA phản hồi và người được quyền pause checkout. Hiện chưa gán tên; OPS-02 không Done khi thiếu roster.

## 5. Runbooks

### RB-01 — Payment UNKNOWN hoặc tiền đến muộn

1. FINANCE/BE lấy payment_id/order_id/reference và trace, che PII; không tạo giao dịch mới.
2. Query provider bằng reference cũ; so merchant/amount/currency.
3. Apply verified result qua cùng handler callback; nếu order closed/hết reserve, giữ refund intent.
4. Query refund cũ khi timeout, không đổi operation key để hoàn lại lần hai.
5. Đóng incident khi counters/payment/order khớp và refund có bằng chứng hoặc obligation/manual queue có owner rõ.

### RB-02 — Saga/stock mắc kẹt

1. Xem intent/version/lease/step, reservation tại inventory và trạng thái shipment/payment.
2. Worker cũ hết lease phải CAS fail; reclaim bằng workflow của service, không sửa reserved tay.
3. ACTIVE có deadline → commit hoặc expire dưới lock; COMMITTED cần return có evidence nếu hủy.
4. Shipment UNKNOWN → xác định bàn giao trước restore; không dùng “đã timeout” làm bằng chứng chưa giao.
5. Xác nhận stock invariant, ledger và order terminal/next work, ghi trace test replay trước đóng.

### RB-03 — Kafka down, outbox backlog, DLQ

1. Kiểm tra broker/ISR/storage/auth và oldest outbox, không xóa PENDING.
2. Nếu backlog vượt budget deadline/business, pause checkout mới; vẫn tiếp nhận callback vào DB nếu DB khỏe.
3. Khôi phục transport; relay cùng event IDs. Sửa poison event/consumer bằng PR tương thích.
4. Replay giới hạn event IDs/time window đã review, giữ IDs và dedupe keys; không reset toàn group tùy ý.
5. Đối chiếu effects/order/payment; theo dõi drain rate và latency trước mở admission.

### RB-04 — Redis outage

1. Cart/catalog đọc DB với concurrency cap; auth/OTP fail closed nếu không giữ được abuse controls.
2. Inventory/quota vẫn DB, không rebuild counters tài chính từ Redis.
3. Warm cache có throttling, version/observed_at; kiểm tra freshness trước mở flash sale.

### RB-05 — Carrier lỗi hoặc hàng hoàn

1. Query shipment theo merchant reference; nếu không xác minh được thì MANUAL, không tự đổi hãng/tạo vận đơn khác.
2. OPS xác nhận bàn giao/cancel bằng evidence. FAILED có thể retry delivery, không tự hủy đơn.
3. Hàng về được kiểm đếm received/restock/damaged; return qua order, operation key ổn định.
4. FINANCE xử lý refund/COD riêng; carrier RETURNED không tự nhập toàn bộ kho hoặc chứng minh tiền hoàn.

### RB-06 — COD chênh lệch

1. FINANCE đối chiếu order total, collected gross, carrier fees và bank net; giữ statement/reference.
2. Thiếu/thừa mở discrepancy, không force SUCCESS.
3. Duplicate statement không tăng collected/settled; cùng ref nhiều order lưu từng payment line.
4. Resolve với evidence và actor; payment SUCCESS chỉ khi đủ nghĩa vụ được xác minh.

### RB-07 — Credential/PII incident

1. Cô lập credential/endpoint ảnh hưởng, bảo toàn log đã giới hạn quyền, incident ID.
2. Rotate/revoke secret/token theo scope; kiểm tra admin auth_version và dependencies.
3. TL/PO/phụ trách pháp lý xác định dữ liệu/thời gian/phạm vi và nghĩa vụ thông báo theo policy O06.
4. Verify phục hồi, regression, postmortem có action owner/deadline; không công bố secret trong ticket.

## 6. Backup, restore và rollback

DB base backup hằng ngày + WAL/PITR theo mục tiêu RPO. DEVOPS phải xác minh restore sang môi trường cô lập, encryption/access và backup failure alert. Kafka retention/replica không thay backup tài chính.

Drill: ghi incident start → chặn writes → chọn restore point từng DB → restore → verify migrations/constraints/counts → reconcile provider và saga/outbox/ledger → replay bounded → E2E → mở traffic. Không coi các DB restore độc lập tự nhất quán.

RPO đo khoảng dữ liệu mất không phục hồi được, RTO từ bắt đầu sự cố đến phục hồi luồng kinh doanh đã xác minh. Nếu provider có giao dịch sau restore point, import qua verified reconciliation giữ business reference để tránh thu/hoàn hai lần.

Rollback app dùng immutable image trước đã tương thích schema; schema destructive cần kế hoạch riêng. Không rollback payment ledger bằng SQL thủ công. Drill phải lưu thời gian, build, data evidence, người thực hiện và gap.

## 7. Go-live checklist và training

- [ ] G2/G3 evidence theo 09/11; không Sev1/Sev2 mở.
- [ ] Policy D/O liên quan có tên/ngày; địa bàn/fee/đổi trả/consent được công bố đúng.
- [ ] Production merchant/domain/callback/secrets khác sandbox; query/refund quyền hạn đã kiểm tra.
- [ ] Admin bootstrap/permissions, on-call roster và redaction verified.
- [ ] Backup/restore/rollback drill pass, dashboards/alerts tới đúng người.
- [ ] OPS thực hành COD confirm, pack, SELF handover, cancel UNKNOWN và kiểm đếm hàng hoàn.
- [ ] FINANCE thực hành partial/full refund, gross/fee/net, late payment và discrepancy.
- [ ] FE VI/EN/ảnh/nội dung thật; không fixture giả hoặc token test.
- [ ] Release record có go/no-go owner, deploy window và cách pause admission.

Checklist này do task OPS-02 hiện thực; trong repo hiện chưa có mục nào được xác nhận đã đạt.
