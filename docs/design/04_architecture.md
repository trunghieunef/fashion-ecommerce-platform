# 04 — System Architecture

B1 · 2026-09-19 · Chủ trì TL + DEVOPS. Theo [08 ADR](08_decisions.md); chưa có deployment đã chạy.

## 1. Ranh giới

Giữ 9 microservices, database per service, không 2PC hoặc truy vấn chéo DB. Java 21/Spring Boot 3, React TypeScript; exact BOM/version trong PLT-01. PostgreSQL là nguồn sự thật cho cart/stock/quota/payment. Redis là cache, rate limit và auth ephemeral.

| Service | Sở hữu | Command/query đồng bộ | Event chính |
|---|---|---|---|
| user | User, token hash, địa chỉ, RBAC | Auth, recipient/secret có scope | USER_CREATED, NOTIFY; consume notification.results |
| catalog | Product/variant/media/collection/review/wishlist | Batch quote, browse | VARIANT_CREATED/CATALOG_CHANGED; consume inventory/promotion/order completed |
| cart | Cart guest/member/version | Snapshot/cleanup cho order; query catalog/inventory/shipping/promotion | Không cleanup từ order event |
| order | Order snapshot, saga, history, cleanup work | Orchestrate inventory/promotion/payment/shipping/cart; snapshot API | Order/notify; consume payment/shipping/inventory |
| inventory | Stock/reservation/ledger/return/subscription | Reserve/commit/release/query/return | Inventory/notify; consume catalog, order completed, notification result |
| payment | Payment/transaction/refund/settlement/reconcile | Create/close/query/refund từ order; provider adapter | Payment/notify; consume COD evidence |
| promotion | Voucher/campaign/quota | Quote/reservations; query saga khi hết hạn | PRICE_CHANGED |
| shipping | Shipment/history/rules | Fee/create/cancel/query; provider/SELF | Shipping/COD/notify |
| notification | Delivery/template/preference | Provider send/query, user secret resolution | Consume NOTIFY duy nhất; publish results |

Các REST call nội bộ đi qua route Gateway private với service identity/caller allowlist. Public ingress chặn /internal. Callback query snapshot không tạo mutation vòng phụ thuộc. Order gửi snapshot đã xác thực cho payment/shipping để tránh query lặp nếu đủ dữ liệu.

## 2. Transaction và điều phối

Order lưu PENDING + items + saga + checkout unique key + outbox trong local TX. Worker ghi intent trước network, nhận kết quả sau bằng lease token/version. Chỉ order ra lệnh reserve/commit/release/create shipment; domain event không phải command thứ hai.

Consumer TX gồm processed_events + effect hoặc durable work, commit offset sau DB commit. Outbox relay claim bằng lease, publish cùng event_id, mark SENT sau ACK. Crash ở giữa tạo duplicate hợp lệ; effect được dedupe bằng DB và business key. aggregate_version là version nghiệp vụ, khác schema version. Để giữ thứ tự nhiều event cùng aggregate/version, relay dùng sequence theo 05.

Recovery worker quét RUNNING/COMPENSATING và các WAITING đã đến deadline/reconcile time. Lease hết hạn được claim lại; mọi handler kiểm tra intent/version và target state. Không giữ row lock trong network call. Timeout mutation = UNKNOWN; query/retry cùng reference. Tombstone ngăn create đến muộn sau cancel.

Order history là audit lịch sử trạng thái, không phải event sourcing. Catalog cache/projection không yêu cầu CQRS framework. Chỉ chia common response/error/trace/outbox primitives có nhu cầu dùng thật; không share entity hoặc domain logic xuyên service.

## 3. Stock, giá, tiền và giao hàng

- Reserve nhiều SKU một TX, khóa SKU theo thứ tự; commit/expire khóa cùng reservation. Constraints bảo vệ stock không âm.
- Quote server giữ actor/cart/address/method/items/versions/totals/deadline; giá thay đổi trả PRICE_CHANGED để khách xác nhận.
- Online giữ 15 phút; COD 24 giờ theo D05. Không gia hạn sau retry.
- PAID chỉ sau verified payment và commit kho/promotion; COD commit khi OPS xác nhận.
- Một payment/order; close ghi closed_at độc lập status tài chính. Refund có reserved amount, unique operation key, UNKNOWN giữ nghĩa vụ.
- Shipment tạo khi PACKING; SHIPPING sau handed_over_at. Cancel UNKNOWN chặn restore kho; sau bàn giao dùng return và kiểm đếm.
- Cart cleanup là durable work độc lập, REST-only, không chặn fulfill.

Luồng và state machine duy nhất ở [06](06_service_flows.md), constraints ở [05](05_database_design.md).

## 4. Dữ liệu và cache

| Dữ liệu | TTL khởi điểm | Khi hết hạn/lỗi |
|---|---|---|
| Catalog detail | 5 phút | DB; invalidate admin/catalog change |
| Catalog list | 30 giây | Query có pagination và limit |
| Stock hiển thị | 5 giây | Fetch inventory hoặc báo chưa xác định; không khẳng định freshness chỉ nhờ TTL |
| Campaign hiển thị | 10 giây | Effective price kiểm tra thời gian tại query/quote |
| Shipping preview | 1 giờ | Giá chốt theo quote expiry, fallback phải xác nhận |
| Guest cart cache | Tối đa 30 ngày | DB vẫn giữ giỏ và expiry |
| OTP challenge | 5 phút | Expire; fail closed khi thiếu auth store |
| Rate limit | Theo cửa sổ từng route | Auth fail closed; browse degraded có giới hạn |

Namespace Redis theo env + service; không đọc key của service khác để thay API. Stock/cache payload có version và observed_at; event cũ không làm lùi. Invalidation task phải durable; nếu TTL hết mà projection chưa cập nhật, query nguồn hoặc hiển thị unknown. Consumer không ACK rồi bỏ mất effect Redis.

PostgreSQL mỗi DB một credential và Flyway history riêng; có thể dùng chung cluster. Backup base + WAL/PITR; restore từng DB phải đối soát saga/payment/outbox trước mở traffic. Query plan/index dựa workload thực; chưa sharding. Search ILIKE ban đầu, search nâng cao Phase 3.

## 5. Resilience và backpressure

Internal query timeout khởi điểm 0.5–2s theo route; mutation checkout chuyển durable processing khi vượt response budget. Retry lỗi transient chỉ khi idempotent; 4xx nghiệp vụ không retry. Provider timeouts theo spec từng adapter ở 15, không áp 2s nội bộ lên tất cả API bên ngoài.

Exponential backoff có jitter, max delay/attempts theo dependency. Kết thúc số lần retry tài chính/kho chuyển MANUAL + alert, không xóa nghĩa vụ. Circuit breaker và bulkhead giới hạn concurrency/connection pool; không giữ hàng trong queue vô hạn.

| Sự cố | Hành vi |
|---|---|
| Kafka down/lag lớn | Outbox tích lũy, saga/payment có thể chậm; alert theo tuổi work; vượt budget dừng nhận checkout mới; webhook vẫn lưu DB |
| Redis down | Browse/cart dùng DB với giới hạn; auth/OTP không bỏ rate limit; stock/quota vẫn DB |
| Promotion unavailable | Khi chưa dùng promotion MVP: no-op. Khi có campaign/voucher: fail quote hoặc pending/reconcile; không âm thầm bỏ giảm giá |
| Shipping fee provider fail | Dùng rule fallback hợp lệ, khách xác nhận trước order; không đổi snapshot sau đó |
| Payment provider fail | UNKNOWN/query; không đổi method hoặc tạo order thay tự động |
| Notification fail | Checkout tiếp tục; notification giữ pending/unknown theo evidence |
| DB unavailable | Không ACK webhook thành công khi chưa commit; request có thể retry cùng key |

## 6. Deployment dự kiến

Local: Compose cho dependency, service chạy JVM/container; provider stub/mail sink. Staging: isolated DB/topics/credentials, provider sandbox. Production topology được duyệt ở O01; namespace không phải biên bảo mật đầy đủ.

Gateway và service cần >= 2 replicas cho mục tiêu HA production, HPA theo tải đo; promotion chỉ deploy Phase 2. Kafka replication/ISR, Redis HA, PostgreSQL failover, Nacos metadata/persistence cần topology có failure domains thực; không coi “3 nodes” là bảo đảm HA. 07 DEP-01 là phương án tham chiếu, chưa quyết định mua hạ tầng.

Public TLS/WAF/Ingress → Gateway → private services; React/ảnh qua object storage/CDN. Nacos registry/config không chứa secrets; Secrets/service accounts riêng. Nacos metadata store riêng được xác minh theo version đã chọn, không mượn DB nghiệp vụ.

CI: lint/unit/integration/contract/security checks → immutable image → staging manifests → ArgoCD → smoke/UAT → authorized production promotion. Main qua PR; feature branches ngắn; môi trường theo manifest, không bắt buộc branch staging lâu dài.

Readiness phản ánh khả năng nhận request của pod, không buộc mọi downstream khỏe rồi làm cả cụm mất ready. Liveness chỉ phát hiện tiến trình hỏng, không restart hàng loạt do Kafka/provider down. Graceful shutdown ngừng nhận work, hoàn tất TX, trả lease an toàn.

## 7. Observability và bảo mật

Prometheus/Grafana metrics, JSON logs/Loki, OTel/Tempo traces. Correlation xuyên HTTP/Kafka; không đưa user_id/order_no làm metric label cardinality cao. Theo dõi riêng checkout acceptance, payment readiness, age UNKNOWN, lease expiry, outbox lag, refund pending, COD outstanding.

JWT verify và ownership tại service; Gateway strip header danh tính của client; service token/mTLS kiểm tra caller/expiry/audience. Cookie auth cần CSRF/origin; admin sensitive kiểm tra auth_version hiện hành. Secret, token, OTP và payment URL nhạy cảm không vào log/cache response công khai.

SLO và test profile ở [11](../quality/11_test_strategy.md); controls, thresholds, runbook và go-live ở [13](../operations/13_operations_security.md). Threat checks và restore drill là acceptance, không chỉ checklist kiến trúc.
