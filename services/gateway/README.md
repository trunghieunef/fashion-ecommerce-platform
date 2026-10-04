# Gateway

`TASK:SEC-01` · `REQ:USR-07`, `REQ:XCT-02`, `REQ:XCT-03`.

Gateway là public ingress local của Sprint 1. Chỉ route
`/api/v1/catalog/**` sang `CATALOG_BASE_URL`; không có catch-all route và không public
`/internal/**`. Trước route, filter global với precedence cao nhất bỏ các header do browser
có thể giả mạo: `X-User-Id`, `X-User-Roles`, `X-Actor-Id`, `X-Service-Name`.

CSRF (ADR-20): `CsrfOriginFilter` chỉ áp dụng cho request ghi (không phải GET/HEAD/OPTIONS/TRACE)
**có cookie**. `Origin` phải nằm trong allowlist. Nếu không có `Origin` thì chỉ chấp nhận
`Sec-Fetch-Site: same-origin`; không có cả hai thì trả 403 `FORBIDDEN` và không forward
(fail closed). Request chỉ dùng Bearer, không có cookie, không bị kiểm tra vì browser không
tự gắn header này. Cookie phải đặt `Secure`, `HttpOnly`, `SameSite` khi task auth/cart tạo cookie.

## Chạy và kiểm tra

Từ repo root, sau khi catalog chạy ở cổng 8081:

```bash
CATALOG_BASE_URL=http://localhost:8081 ./mvnw -pl services/gateway spring-boot:run
./mvnw -pl services/gateway test
curl -i 'http://localhost:8080/api/v1/catalog/products?limit=1' \
  -H 'X-User-Id: attacker' -H 'X-User-Roles: SUPER_ADMIN'
curl -i http://localhost:8080/internal/api/v1/platform/ping
```

Public catalog request phải nhận response từ catalog; upstream không nhận các header identity
trên. Internal path phải là HTTP 404. Gateway dùng timeout connect 500 ms và response 2 s;
timeout không được biến mutation thành retry hay thành công giả.

## Config và observability

| Variable | Default | Ý nghĩa |
|---|---|---|
| `GATEWAY_PORT` | `8080` | Public listener local |
| `CATALOG_BASE_URL` | `http://localhost:8081` | Catalog upstream trong default profile |
| `GATEWAY_ALLOWED_ORIGINS` | `http://localhost:4173` | Origin được phép gửi request ghi kèm cookie, phân tách bằng dấu phẩy; để rỗng thì từ chối tất cả |

Actuator expose `health`, `info`, `metrics` trên cùng port public 8080; chấp nhận cho local,
phải tách management port hoặc chặn trước staging (PLT-04). Gateway không có database,
migration, Kafka topic hay credential riêng ở S1.

Default route cố ý dùng URI cấu hình để cô lập Gateway boundary. Profile `nacos-compat` bật
Nacos discovery (`NACOS_SERVER_ADDR`, `NACOS_USERNAME`, `NACOS_PASSWORD`); đặt
`CATALOG_BASE_URL=lb://catalog-service` để route qua discovery (Compose:
`GATEWAY_CATALOG_BASE_URL`). Proof: `bash scripts/nacos-compat-check.sh`. Service identity
cho call nội bộ nằm ở [platform-security](../platform-security/README.md) (ADR-19); Gateway
không route `/internal/**` nên không tự cấp service token.

Trong Compose, Gateway chạy từ `Dockerfile` và chỉ publish `127.0.0.1:8080`.
