# platform-security

`TASK:SEC-01` · REQ: USR-07, USR-08, XCT-02, XCT-03 · quyết định: ADR-19 (08).

Thư viện Java xác thực danh tính khi service gọi nhau qua `/internal/api/v1`. Đây không phải
service chạy riêng. Chỉ phụ thuộc `spring-security-oauth2-jose`, version theo Spring Security
BOM (do Boot import); thư viện này kéo theo Nimbus JOSE.

| Class | Việc làm |
|---|---|
| `ServiceTokenIssuer` | Caller ký token ES256 bằng khóa riêng của mình: `iss = sub = kid =` tên service, `aud =` đúng một service đích, `exp = iat + 60s`, có `jti` |
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
- Chưa có service nào gọi `/internal` thật. Việc nối thư viện vào service (filter MVC, cấu hình
  key) làm trong task đầu tiên có internal call (CART/ORD/INV).

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

- Chưa có token người dùng (member/admin JWT, JWKS của user-service, `auth_version`):
  thuộc USR-01/USR-02.
- Chưa kiểm tra trùng `jti` (replay trong 60s): mutation internal đã cần Idempotency-Key và
  business key (03 §4). Chưa có mTLS; đây là lựa chọn của ADR-19.
