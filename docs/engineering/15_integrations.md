# 15 — Tích hợp bên ngoài và điều kiện sandbox

B1 · 2026-09-19 · Owner BE phụ trách adapter + DEVOPS + PO/FINANCE.

## 1. Hợp đồng adapter chung

Mỗi provider cần create/query/callback/close nếu hỗ trợ/refund hoặc cancel tương ứng. Kết quả normalized: thành công đã xác minh, thất bại chắc chắn, hoặc UNKNOWN. Network timeout không chứng minh phía ngoài chưa thực hiện.

Tạo local intent và stable provider_request_id trước gọi mạng. Retry dùng cùng business reference theo khả năng provider; query trước retry khi UNKNOWN. Provider không hỗ trợ query/idempotency đủ tin cậy thì MANUAL, không tự tạo giao dịch hoặc vận đơn thứ hai.

Không dùng secret/URL/account thật trong repo. Mỗi adapter có version/profile, nguồn chính thức, ngày đối chiếu, sandbox account owner, request/response đã che và các test negative. Caller không gửi amount từ browser làm authority.

## 2. Những điểm đã đối chiếu nguồn chính thức

Đối chiếu trang công khai ngày 2026-09-19; vẫn cần xác nhận profile của merchant và sandbox trước bật thật.

| Provider/profile | Contract B1 dùng | Nguồn |
|---|---|---|
| VNPay PAY 2.1.0, GET profile | IPN GET, amount nhân 100; ký HMAC-SHA512 theo profile chọn; ACK JSON RspCode/Message sau xử lý | [Hướng dẫn PAY](https://sandbox.vnpayment.vn/apis/docs/thanh-toan-pay/pay.html), [chuyển thuật toán](https://sandbox.vnpayment.vn/apis/docs/chuyen-doi-thuat-toan/changeTypeHash.html) |
| MoMo notification | POST JSON, verify theo signature field mapping; ACK HTTP 204 không body | [Payment Notification](https://developers.momo.vn/v3/vi/docs/payment/api/result-handling/notification/) |
| MoMo refund/query | Refund và query kết quả riêng; tài liệu refund yêu cầu timeout tối thiểu 30 giây cho lời gọi API đó | [Reverse & Refund](https://developers.momo.vn/v3/docs/payment/api/payment-api/refund/) |

Vì vậy timeout 2 giây nội bộ không được áp lên mọi provider call. Worker provider chạy ngoài transaction và ngoài response budget checkout. VNPay merchant dùng profile POST khác cần đổi route/fixtures tương ứng trước triển khai; B1 không mặc định mọi profile giống GET PAY.

Không sao chép công thức nối secret “SHA-256” hoặc ACK form minh họa của tài liệu cũ. Encoding, canonical fields, chữ ký callback/query/refund phải có golden fixtures của đúng profile. Browser ReturnURL chỉ điều hướng/đọc trạng thái server.

## 3. Ma trận tích hợp và acceptance

| Adapter / task | Thông tin cần PO/DEVOPS cấp | Test bắt buộc trước Done |
|---|---|---|
| VNPay / PAY-02 + PAY-04 | Sandbox merchant, HashSecret bằng secret manager, profile, callback domain, query/refund permission, refund SLA | create/query, valid/invalid signature/amount/reference, duplicate/late IPN, callback trước HTTP response, full/partial refund và UNKNOWN query |
| MoMo / PAY-03 + PAY-04 | Partner credentials, product/requestType/profile, redirect/IPN domains, query/refund quyền | create/query/notify ACK, pending/final result codes, duplicate/refund query, merchant mismatch |
| GHN / SHP-03 subtask | Account/shop/token, address mapping, callback auth, fee/COD rules | Fee/create/query/cancel, duplicate request, status mapping, handed_over evidence, failed-redelivery/return, gross-fee-net |
| GHTK / SHP-03 subtask | Account/token, API version, callback auth, query khả năng, COD statement | Như GHN nhưng fixture/source riêng, không giả cùng HMAC header |
| Viettel Post / SHP-03 subtask | Account/token lifecycle, API version, callback auth, địa chỉ, settlement | Như trên; thêm token expiry/refresh và outage |
| SELF / SHP-01/02 | Vùng giao, bảng phí/COD fee, người giao, bằng chứng OPS | State guard, cancel-before-create, handover, delivered/collected/settled độc lập |
| Email / NOT-01 | Provider/domain/sender, sandbox hoặc mail sink, idempotency/query support | Delivery accepted, bounce/error/timeout query, duplicate key, template VI/EN, no PII logs |
| SMS / USR-03 | Provider/brandname, OTP template, hạn mức/cost, delivery query | Expired challenge không gửi, delayed ACK, retry cap, rate limit; không log code |
| Google/Facebook / USR-04 | OAuth apps, redirect allowlist, audience/issuer/config phù hợp | State/nonce invalid, redirect mismatch, verified email/link conflict, revoke/unlink |

Nguồn carrier/email/SMS/OAuth cụ thể chưa được chốt; adapter owner phải thêm URL/version/ngày vào release evidence. Task mock có thể hoàn tất trước, task sandbox vẫn Blocked nếu chưa có account. Không cam kết idempotency/query provider khi chưa chứng minh.

## 4. Callback processing và evidence

1. Read raw input với size limit; verify bằng adapter đúng profile; không log raw token/signature.
2. Map provider result và money units, kiểm tra merchant/reference/amount/currency.
3. Lock resource, dedupe provider_event_key + business invariant; TX evidence sanitized + status + outbox.
4. ACK theo spec sau commit. DB fail không ACK success. Không mặc định provider luôn retry; recovery query vẫn cần.
5. Query result dùng cùng hàm apply verified outcome với callback.

Golden fixtures gồm request canonical string đã thay secret bằng test secret, expected signature, currency/amount conversions, status mapping, ACK, invalid cases. Fixture là synthetic/sandbox đã che; không chứa production data.

## 5. Trước bật production

- [ ] Người sở hữu merchant/account, version/profile và sandbox proof.
- [ ] TLS/domain/allowlist/callback method/ACK khớp account.
- [ ] Secret prod tách test, rotation và access rights.
- [ ] Min/max amount, phí, giới hạn refund/query, deadline và timezone được ghi vào adapter config/tests.
- [ ] Stable reference/query và MANUAL fallback được xác minh; refund có FINANCE training.
- [ ] Rate limits/timeouts/retry budget và support contact/SLA có owner.
- [ ] Provider smoke được cho phép theo release plan; không dùng tiền thật để load test.

Các checklist chưa được đánh dấu đạt ở repo docs-only. Kết quả tích hợp thuộc task/release evidence, không suy từ việc đọc tài liệu provider.
