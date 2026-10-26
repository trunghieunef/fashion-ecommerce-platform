# PAY-02 — payment-service: VNPay PAY 2.1.0 (tạo link, IPN, return, QueryDR) với sandbox thật

`TASK:PAY-02` · `REQ:PAY-01`, `REQ:PAY-04`, `REQ:PAY-06` (query UNKNOWN tối thiểu), `REQ:PAY-07` · dependency PAY-01, ORD-02
(Done local). Gate O03: chủ dự án đăng ký **merchant sandbox** VNPay (2026-10-25); merchant production vẫn mở.
Trạng thái: **đã duyệt** 2026-10-25 (chủ dự án yêu cầu thêm §10 rồi viết plan). Chủ dự án giao agent tự chốt mặc định; lựa chọn ghi ở §11.
Nguồn: 02 PAY-01/04/06/07, 03 §4 (`POST /payments`) và §6 (provider callbacks), 05 §9, 06 §7.1 và §17.1, 15 §1–§3, T06/T07
(06 §13); tài liệu VNPay đối chiếu 2026-10-25: [PAY 2.1.0](https://sandbox.vnpayment.vn/apis/docs/thanh-toan-pay/pay.html),
[QueryDR/Refund](https://sandbox.vnpayment.vn/apis/docs/truy-van-hoan-tien/querydr&refund.html).

## 1. Mục tiêu và ngoài phạm vi

- Order tạo payment **VNPAY** qua `POST /internal/api/v1/payments` như COD; payment-service trả `payment_url` VNPay đã ký,
  giữ **một `vnp_TxnRef` = `order_no`** suốt vòng đời (PAY-07).
- VNPay báo kết quả qua **IPN** (server → server): verify chữ ký, merchant, reference, amount, currency → một transaction
  ghi bằng chứng + trạng thái + outbox → rồi mới ACK JSON `RspCode` (PAY-04).
- **QueryDR worker** hỏi VNPay các payment chưa có kết quả (chưa nhận IPN, IPN lỗi, máy local không nhận được IPN) và áp kết
  quả bằng **cùng hàm** với IPN (06 §7.1).
- **Return** của trình duyệt chỉ chuyển hướng về trang đơn trên storefront; không đổi trạng thái tiền.
- Chạy được với **VNPay sandbox thật** trên máy local: tạo link → trả bằng thẻ test của VNPay → QueryDR (bắt buộc) và IPN
  qua tunnel (tùy chọn) → `SUCCESS` + `PAYMENT_COMPLETED`.

Acceptance (10 dòng PAY-02, 15 §3 hàng VNPay): create/query/IPN/return sandbox; chữ ký/amount/merchant/reference sai không
bao giờ thành `SUCCESS`; IPN trùng/đến muộn một effect; callback trước HTTP response của create; T06 (success lặp một
effect), T07 phần payment (success đến khi order còn PENDING được ghi durable — order đã xử lý ở ORD-02).

Không làm:

- Refund VNPay (PAY-04), MoMo (PAY-03), đối soát batch hằng ngày (PAY-06 Phase 2).
- Order chọn VNPAY khi checkout, `WAITING_PAYMENT`, commit kho sau khi trả tiền (ORD-05); storefront VNPay (WEB-04). Quote
  order vẫn trả `PAYMENT_METHOD_UNAVAILABLE` cho VNPAY tới ORD-05.
- Merchant production, tên miền thật, deploy (O03, G0/G2).

Anti-goals: tin ReturnURL; ACK trước commit; tạo `TxnRef` mới khi thử lại; coi timeout là thất bại; lưu `vnp_HashSecret`
hoặc dữ liệu thẻ vào Git/log/DB/fixture; dùng thư viện VNPay ngoài (JDK `Mac` + `RestClient` có sẵn là đủ).

## 2. Cấu hình và secret

| Biến | Mặc định | Ghi chú |
|---|---|---|
| `PAYMENT_VNPAY_TMN_CODE` | rỗng | Rỗng = VNPAY tắt: create VNPAY vẫn 422 `PAYMENT_METHOD_UNAVAILABLE`, worker không chạy, IPN trả `99` |
| `PAYMENT_VNPAY_HASH_SECRET` | rỗng | Chỉ từ env; không log, không vào evidence |
| `PAYMENT_VNPAY_PAY_URL` | `https://sandbox.vnpayment.vn/paymentv2/vpcpay.html` | |
| `PAYMENT_VNPAY_API_URL` | `https://sandbox.vnpayment.vn/merchant_webapi/api/transaction` | QueryDR |
| `PAYMENT_VNPAY_RETURN_URL` | `http://localhost:8080/api/v1/payments/vnpay/return` | Qua Gateway |
| `PAYMENT_STOREFRONT_URL` | `http://localhost:5173` | Đích redirect duy nhất (allowlist) |
| `PAYMENT_VNPAY_LINK_TTL` | `15m` | Hạn link, không vượt `expires_at` của order |

- Local: `PAYMENT_VNPAY_TMN_CODE`/`PAYMENT_VNPAY_HASH_SECRET` do chủ dự án tự điền ở `infra/local/.env` (**gitignore**); repo chỉ có
  placeholder rỗng ở `.env.example` (đổi theo plan: dùng `infra/local/.env`, không có `vnpay.local.env`). CI không có secret: VNPAY tắt, mọi test dùng secret giả lập.
- `scripts/scan-secrets.sh` (gitleaks) phải pass; log JSON hiện có đã che giá trị chuỗi (PLT-05); Gateway che query của
  `/api/v1/payments/vnpay/**` khỏi access log (03 §6).

## 3. Schema

Không đổi bảng `payments` (05 §9 đã có `payment_url`, `provider_txn_id`, `next_reconcile_at`, unique `(method,
provider_txn_id)`; `payment_transactions.source` đã có `WEBHOOK`, `QUERY`). Migration **V002** chỉ thêm cột
`payments.provider_created_at timestamptz`: `vnp_CreateDate` đã ký trong link, cần lại đúng giá trị cho
`vnp_TransactionDate` của QueryDR (tới giây, GMT+7). Null với COD. Return không ghi gì (§6).

## 4. State machine (tập con 06 §17.1 cho VNPAY)

| Từ → đến | Khi |
|---|---|
| (mới) → `CREATED` | Create VNPAY: row + `payment_url` + `CREATE` evidence + `PAYMENT_CREATED`, cùng transaction. Không gọi mạng (link VNPay chỉ là URL đã ký) |
| `CREATED` → `PROCESSING` | QueryDR thấy giao dịch tồn tại nhưng `vnp_TransactionStatus = 01` (chưa xong) |
| `CREATED`/`PROCESSING`/`UNKNOWN`/`FAILED` → `SUCCESS` | IPN hoặc QueryDR đã xác minh `ResponseCode = 00` và `TransactionStatus = 00`, đúng merchant/reference/amount×100/VND. `FAILED` vẫn nhận late success; giữ `closed_at` (order phải refund) |
| `CREATED`/`PROCESSING`/`UNKNOWN` → `FAILED` | IPN/QueryDR xác minh thất bại chắc chắn (`TransactionStatus` 02, hoặc `ResponseCode` ≠ 00 (trừ 07) và `TransactionStatus` ≠ 00 trên IPN), hoặc QueryDR `91` (không có giao dịch) **sau** hạn link + 15 phút; close của order như PAY-01 |
| `SUCCESS` → (giữ) | Mọi IPN/query sau đó: ghi evidence nếu mới, không đổi trạng thái, không phát event lần hai |

`UNKNOWN` không phát sinh trong PAY-02: create không gọi mạng; lỗi mạng/timeout của QueryDR chỉ dời lịch query (query là
đọc, không phải mutation). `TransactionStatus` 04/07 (đảo/nghi gian lận), `ResponseCode` 07 (trừ tiền thành công, nghi ngờ) hoặc cặp mâu thuẫn
`ResponseCode` ≠ 00 với `TransactionStatus` 00 (review PR #41 I1: có thể đã trừ tiền, không được thành `FAILED` hủy đơn) → giữ
trạng thái, mở `reconciliation_items` `PROVIDER_REVIEW` cho FINANCE. QueryDR `91` cho payment đã `FAILED` (close) chỉ ghi
evidence để dừng query, không phát event (review M1). `AMOUNT_MISMATCH` ghi `observed_amount` làm tròn lên (review M2).

## 5. API nội bộ: create VNPAY

`POST /internal/api/v1/payments` (đã có, PAY-01) thêm nhánh `method = VNPAY`:

- Thêm trường **không băm** (không vào `request_hash`/snapshot vì không phải dữ liệu tiền): `client_ip` (bắt buộc với VNPAY,
  IPv4 hợp lệ hoặc IPv6 literal, tối đa 45 ký tự → `vnp_IpAddr`; định dạng kiểm ở controller trước mọi trạng thái) và `locale` (`vi`|`en`, mặc định `vi` → `vnp_Locale` `vn`|`en`). COD bỏ qua hai trường.
- Link: `vnp_Version=2.1.0`, `vnp_Command=pay`, `vnp_TmnCode`, `vnp_Amount = Math.multiplyExact(amount, 100)`,
  `vnp_CurrCode=VND`, `vnp_TxnRef = order_no`, `vnp_OrderInfo = "Thanh toan don hang {order_no}"` (ASCII, không dấu),
  `vnp_OrderType=other`, `vnp_ReturnUrl`, `vnp_CreateDate` (= `provider_created_at`, GMT+7 `yyyyMMddHHmmss`),
  `vnp_ExpireDate = min(order.expires_at, created + PAYMENT_VNPAY_LINK_TTL)`; không gửi `vnp_BankCode` (khách chọn trên
  VNPay). Chữ ký: tham số `vnp_*` khác rỗng, sắp xếp tên tăng dần, `name=URLEncode(value)` nối `&`, HMAC-SHA512 hex.
- Response thêm `payment_url` (null với COD) và `payment_url_expires_at` (hạn link, ruling R2). Replay/cùng snapshot trả **cùng link** (link không đổi
  vì `TxnRef` không được trùng trong ngày). Đã close → 409 `PAYMENT_CLOSED` (như PAY-01); link hết hạn → vẫn trả link cũ
  kèm `payment_url_expires_at` (order quyết định; không tạo `TxnRef` mới).
- `amount = 0` vẫn 422; VNPAY tắt (§2) → 422 `PAYMENT_METHOD_UNAVAILABLE`, nhưng payment đã có của order luôn được trả trước
  (review PR #41 M4: tắt VNPAY không làm retry của order bị 422 rồi hủy đơn). Order đã quá `expires_at` khi tạo link → 409
  `PAYMENT_CLOSED`, không tạo payment (review M5: `vnp_ExpireDate` phải sau `vnp_CreateDate`).
- Evidence VNPay ghi `payment_transactions.provider_txn_id = vnp_TransactionNo` (review M6).

## 6. Endpoint public (qua Gateway, route `payment-vnpay-public` chỉ hai path)

**`GET /api/v1/payments/vnpay/ipn`** (03 §6) — không envelope của shop; luôn HTTP 200 + JSON `{"RspCode","Message"}`:

| Thứ tự kiểm | Kết quả |
|---|---|
| VNPAY tắt hoặc thiếu tham số | `99` |
| Chữ ký sai (so sánh constant-time) | `97` — không ghi DB, log cảnh báo đã che |
| `vnp_TmnCode` khác merchant cấu hình | `97` (không xác minh được cho merchant này) |
| Không có payment VNPAY với `order_no = vnp_TxnRef` | `01` |
| `vnp_Amount ≠ amount × 100` | `04` + `reconciliation_items` `AMOUNT_MISMATCH` (một lần/transaction), không đổi trạng thái |
| Payment đã `SUCCESS` (hoặc đã `FAILED` mà IPN này cũng thất bại) | `02` "Order already confirmed"; evidence mới vẫn được ghi |
| Hợp lệ | Áp kết quả (§7) trong một transaction → commit → `00` |
| Lỗi DB/không mong đợi | `99` (VNPay gửi lại tối đa 10 lần, cách 5 phút) |

**`GET /api/v1/payments/vnpay/return`** — không đọc/ghi DB. Nếu `vnp_TxnRef` đúng định dạng `order_no` → 302 tới
`{PAYMENT_STOREFRONT_URL}/orders/{order_no}`; còn lại → 302 tới `{PAYMENT_STOREFRONT_URL}/`. Không chuyển tiếp tham số
`vnp_*`. Trang đơn đọc trạng thái từ server (WEB-04 hiển thị).

## 7. Áp kết quả đã xác minh (một hàm cho IPN và QueryDR)

`applyVerified(source WEBHOOK|QUERY, evidence)` trong một transaction:

1. `pg_advisory_xact_lock` theo order (như PAY-01) → đọc payment.
2. Insert `payment_transactions` `(payment_id, source, provider_event_key)`: IPN dùng
   `vnp_TransactionNo:vnp_ResponseCode:vnp_TransactionStatus`, query dùng thêm `vnp_ResponseId`; trùng → bỏ qua phần còn lại.
   `sanitized_payload` chỉ allowlist: `vnp_TxnRef`, `vnp_Amount`, `vnp_ResponseCode`, `vnp_TransactionStatus`,
   `vnp_TransactionNo`, `vnp_BankCode`, `vnp_CardType`, `vnp_PayDate`; không `vnp_SecureHash`, không `vnp_BankTranNo`.
3. Chuyển trạng thái theo §4; `SUCCESS` ghi `paid_at` = `vnp_PayDate` (GMT+7 → UTC), `provider_txn_id` =
   `vnp_TransactionNo`, version + 1.
4. Outbox: `PAYMENT_COMPLETED` (có `provider_txn_id`) khi lần đầu `SUCCESS`; `PAYMENT_FAILED` reason
   `PROVIDER_DECLINED`/`EXPIRED` khi lần đầu `FAILED`. Không event khi trạng thái không đổi.

Order hiện bỏ qua `PAYMENT_COMPLETED` của order không tồn tại (ORD-02), nên payment sandbox với order giả lập an toàn.

## 8. QueryDR worker

- `@Scheduled` mỗi 30 s: chọn payment `VNPAY` trạng thái `CREATED`/`PROCESSING`, `closed_at` bất kỳ (kể cả `FAILED` do close
  khi chưa có evidence `WEBHOOK`/`QUERY` thất bại; close giữ `next_reconcile_at`), `next_reconcile_at <=
  now()` (đặt lúc create = created + 1 phút), `limit 20 for update skip locked`, dời `next_reconcile_at` trước khi commit;
  gọi VNPay **ngoài** transaction (timeout 10 s), rồi `applyVerified(QUERY, …)`.
- Lịch: 1, 2, 5, 10, 30 phút sau create, rồi mỗi 30 phút tới hạn link + 24 giờ; sau đó dừng (FINANCE xem qua admin).
- Request: `vnp_RequestId` UUID không gạch (32 ký tự), `vnp_TransactionDate = provider_created_at`, `vnp_IpAddr` cấu hình
  của server, checksum `RequestId|Version|Command|TmnCode|TxnRef|TransactionDate|CreateDate|IpAddr|OrderInfo` HMAC-SHA512.
  Response: verify checksum theo thứ tự 15 trường của VNPay (trường thiếu = chuỗi rỗng); sai → bỏ qua, log cảnh báo.
- `ResponseCode` `00` → áp theo `TransactionStatus`; `91` → `FAILED` `EXPIRED` chỉ khi quá hạn link + 15 phút, trước đó
  chờ; `94`/`99`/lỗi mạng → thử lại theo lịch; `02`/`97` → log lỗi cấu hình, thử lại.

## 9. Test và bằng chứng sandbox

PostgreSQL Testcontainers như PAY-01; VNPay HTTP giả bằng `MockRestServiceServer`/JDK `HttpServer`; secret giả lập.

- `VnPaySignerTest`: golden fixture `tests/provider-fixtures/vnpay/` (link, IPN, query request/response) tính bằng secret giả
  lập, kiểm canonical string và chữ ký; tiếng Việt/khoảng trắng/`+` được encode đúng; amount overflow bị chặn.
- `VnPayCreateIntegrationTest`: link đúng tham số, replay cùng link, close → 409, VNPAY tắt → 422, COD không đổi.
- `VnPayIpnIntegrationTest`: thành công → `SUCCESS` + một `PAYMENT_COMPLETED`; gửi lặp → `02`, một event (T06); chữ ký sai,
  merchant sai, amount sai, reference không có → `97`/`97`/`04`/`01` và không `SUCCESS`; thất bại rồi late success →
  `SUCCESS`; payment đã close nhận success → `SUCCESS` giữ `closed_at`; DB lỗi → `99`.
- `VnPayQueryIntegrationTest`: query success = cùng effect và dedupe với IPN (IPN rồi query, query rồi IPN); `91` trước/sau
  hạn; response sai checksum bị bỏ qua; timeout dời lịch không đổi trạng thái.
- Gateway: route chỉ khớp hai path; `/internal/**` vẫn 404; smoke CI kiểm IPN thiếu tham số → `99`.
- **Sandbox thật (local, không CI):** `scripts/vnpay-sandbox.sh` ký service token order-service bằng key local, tạo payment
  VNPAY cho order giả lập, in `payment_url`; chủ dự án mở link, trả bằng thẻ test trong tài liệu VNPay; script chờ QueryDR
  → `SUCCESS`. Tùy chọn: chạy `cloudflared tunnel --url http://localhost:8080` (quick tunnel, không tài khoản), đặt IPN URL
  trong trang merchant sandbox, trả đơn thứ hai → có evidence `WEBHOOK`. Evidence ghi profile/phiên bản/ngày, mã đã che
  (`order_no` giả lập, 4 số cuối `TransactionNo`), không chụp secret. Máy 7.4 GB: chỉ chạy PostgreSQL, Kafka,
  payment-service, Gateway.

Docs: `services/payment-service/README.md`, `payment-internal.yaml` + contract mới cho hai endpoint public (`payment.yaml`),
03 §4/§6, 05 (cột mới), 06 §7.1 (QueryDR), 15 §2/§3 (ngày đối chiếu, profile), 08 (quyết định §11), 10, 12 (lệnh sandbox),
`CLAUDE.md`, evidence.

## 10. Chuyển sang domain/production (checklist, ngoài PAY-02)

Không đổi code: mọi URL và merchant là biến (§2). Ví dụ domain `shop.vn`:

| Biến / cấu hình | Local sandbox | Có domain |
|---|---|---|
| `PAYMENT_VNPAY_TMN_CODE`, `PAYMENT_VNPAY_HASH_SECRET` | Merchant sandbox, file env local | Merchant thật, secret manager (16) |
| `PAYMENT_VNPAY_PAY_URL`, `PAYMENT_VNPAY_API_URL` | URL sandbox | URL production VNPay cấp khi ký hợp đồng |
| `PAYMENT_VNPAY_RETURN_URL` | `http://localhost:8080/api/v1/payments/vnpay/return` | `https://shop.vn/api/v1/payments/vnpay/return` |
| `PAYMENT_STOREFRONT_URL` | `http://localhost:5173` | `https://shop.vn` |
| IPN URL (khai trong trang merchant VNPay) | Tunnel tạm hoặc không dùng | `https://shop.vn/api/v1/payments/vnpay/ipn` |

Lưu ý trước khi bật thật (O03, G0/G2):

1. Hỏi VNPay profile của hợp đồng production; khác PAY 2.1.0 GET (ví dụ IPN POST) thì sửa adapter + fixture trước (15 §2).
2. IPN bắt buộc HTTPS: domain cần chứng chỉ (16).
3. `client_ip` thật của khách do order lấy từ Gateway khi bật VNPAY (ORD-05); script sandbox dùng IP giả lập.
4. Tùy chọn: chỉ nhận IPN từ dải IP VNPay công bố ở Gateway/ingress; chữ ký vẫn là kiểm tra chính.

## 11. Lựa chọn mặc định của agent (chủ dự án duyệt cùng spec 2026-10-25)

| # | Lựa chọn | Lý do / tác động |
|---|---|---|
| D1 | PAY-02 chỉ ở payment-service + Gateway; order/storefront VNPAY để ORD-05/WEB-04 | Đúng dependency backlog; sandbox chạy được bằng script gọi internal create |
| D2 | `vnp_TxnRef = order_no`, một link/đơn, không tạo link mới khi hết hạn | PAY-07; VNPay cấm trùng `TxnRef` trong ngày |
| D3 | Create không gọi mạng → `CREATED` + link; không có `UNKNOWN` ở PAY-02 | Link PAY 2.1.0 chỉ là URL đã ký; 06 §17.1 cấm `PROCESSING` chỉ vì khách mở trang |
| D4 | `client_ip`, `locale` là trường không băm của create | VNPay bắt buộc `vnp_IpAddr`; không phải dữ liệu tiền nên không đổi `request_hash` |
| D5 | IPN: chữ ký/merchant sai `97`, amount sai `04` + reconciliation, đã xác nhận `02`, lỗi `99` | Theo bảng RspCode của VNPay; VNPay tự gửi lại với `01/04/97/99` |
| D6 | QueryDR là đường xác nhận chính khi local, IPN qua tunnel là tùy chọn | Máy local/CI không nhận được request từ Internet; IPN yêu cầu SSL |
| D7 | Query lỗi mạng chỉ dời lịch; `91` chỉ thành `FAILED` sau hạn link + 15 phút | Query là đọc; link hết hạn không tự chứng minh chưa thu (06 §7.1) |
| D8 | Return chỉ 302 về storefront, không verify/không ghi | 15 §2: return chỉ điều hướng; giảm bề mặt tấn công |
| D9 | Không thư viện VNPay; JDK HMAC + Spring `RestClient` | Ít dependency (AGENTS) |
| D10 | Secret qua env, file local gitignore; CI không có secret | AGENTS: không secret trong Git/log |
