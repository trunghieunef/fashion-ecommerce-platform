# platform-security

`TASK:SEC-01` · REQ: USR-07, USR-08, XCT-02, XCT-03 · quyết định: ADR-19 (08).

Thư viện Java xác thực danh tính khi service gọi nhau qua `/internal/api/v1`. Đây không phải
service chạy riêng. Chỉ phụ thuộc `spring-security-oauth2-jose`, version theo Spring Security
BOM (do Boot import); thư viện này kéo theo Nimbus JOSE.

| Class | Việc làm |
|---|---|
| `ProxyClientIp` | Chuẩn hóa IP literal (IPv4/IPv6); chỉ parse header sau khi peer thuộc allowlist IP/hostname. IPv4 kiểm octet 0..255, không đưa header vào DNS; loại zone của socket peer, từ chối zone trong header. DNS chỉ dành cho hostname operator cấu hình, lỗi thì dùng peer |
| `ServiceTokenIssuer` | Caller ký token ES256 bằng khóa riêng của mình: `iss = sub = kid =` tên service, `aud =` đúng một service đích, `exp = iat + 60s`, có `jti` |
| `AccessTokenVerifier` | Kiểm access token member/admin (ADR-21): ES256 với public key cấu hình theo `kid` (`parseKeys("kid:base64,…")`, 2 kid khi xoay khóa), `iss=user-service`, `aud=fashion-api`, còn hạn, `iat` không ở tương lai, sống tối đa 15 phút, `sub` là UUID, có `auth_version` và `permissions` (mảng chuỗi, có thể rỗng). Chỉ nhận JWS. Trả `Actor(userId, authVersion, permissions)` hoặc rỗng; service vẫn tự kiểm permission code và ownership |
| `ServiceTokenVerifier` | Service đích kiểm tra và từ chối mặc định: chỉ chấp nhận ES256, ký bằng public key được allowlist cho caller đó (theo `kid`/`iss`); `aud` phải chứa chính service này; `sub = iss`; còn hạn; `iat` không ở tương lai quá 5s, `exp > iat`, không sống quá TTL (lệch đồng hồ tối đa 5s). Chỉ nhận JWS: JWE và token không ký bị từ chối mà không ném lỗi. Trả về tên caller, hoặc rỗng nếu không hợp lệ |

Endpoint internal vẫn phải tự kiểm tra caller có nằm trong allowlist của operation đó không
(03 §4), và vẫn kiểm tra quyền/ownership của actor. Token hợp lệ chỉ chứng minh request đến
từ service nào, không cấp quyền nghiệp vụ.

## Cấu hình và secret

- Mỗi service có một cặp khóa EC P-256 riêng cho từng môi trường. Private key được nạp vào
  qua Secret (env/file mount), không đặt trong Git, image hay Nacos.
- Public key của các caller được phép là cấu hình không bí mật, khai báo theo service đích.
- Xoay khóa: tạm thời allowlist cả key mới lẫn key cũ, deploy caller dùng key mới, rồi gỡ key cũ.
  Token chỉ sống 60s nên không cần danh sách thu hồi.
- Service đích đầu tiên dùng `ServiceTokenVerifier`: user-service (`TASK:USR-01` phần 1b-i,
  `GET /internal/api/v1/users/notification-secrets/{id}`, caller allowlist `USER_INTERNAL_CALLERS`
  theo dạng `service:base64-X.509`, token gửi bằng `Authorization: Bearer`). Chưa có caller thật
  (notification-service thuộc NOT-01).

## Test

```bash
./mvnw -pl services/platform-security test
```

Các trường hợp bị từ chối: sai audience, caller ngoài allowlist, khóa sai, hết hạn, sống quá
TTL, `iat` ở tương lai, `exp ≤ iat`, `sub ≠ iss`, `alg: none`, HS256, JWE, token rác hoặc rỗng. Test cũng khẳng định khi từ chối thì
token không bị ghi ra log. Không dùng network hay Docker.

## Che dữ liệu trong log (XCT-06, PLT-05)

`LogRedactor.redact` che: giá trị của key chứa `password|passwd|secret|token|otp` (kể cả tên
ghép như `access_token`, `refreshToken`) ở dạng `key=value`, `key: value` và JSON `"key":"value"`,
giá trị trong ngoặc kép/đơn được che tới hết dấu đóng; `Bearer …`; chuỗi dạng JWT; email; số điện
thoại VN (`0` hoặc `+84` + 9 chữ số). `PiiRedactingJsonCustomizer` che **theo tên field** trước:
field MDC hoặc SLF4J key-value có tên nhạy cảm (`password`, `token`, `otp`, `authorization`,
`cookie`, `credential`…) bị thay bằng `[redacted]` dù giá trị kiểu gì; mọi giá trị chuỗi khác
đi qua `LogRedactor`. Bật bằng
`logging.structured.json.customizer=vn.fashion.platform.security.PiiRedactingJsonCustomizer`.
Đây chỉ là lưới an toàn: code vẫn không được log dữ liệu nhạy cảm. Regex không nhận diện được
họ tên hay địa chỉ trong text tự do.

## Giới hạn

`ProxyClientIpTest` dùng child JVM với `jdk.net.hosts.file` test-only để ánh xạ IPv4 sai
octet thành hostname có địa chỉ; regression phải từ chối dù resolver có trả lời.
Không gọi DNS public và không thay resolver của JVM chạy các test khác.

- Token người dùng do user-service phát hành (ADR-21); thư viện này chỉ verify, không có JWKS.
  Quyền trong `permissions` có thể cũ tối đa 15 phút với service không phải user-service.
- Chưa kiểm tra trùng `jti` (replay trong 60s): mutation internal đã cần Idempotency-Key và
  business key (03 §4). Chưa có mTLS; đây là lựa chọn của ADR-19.
