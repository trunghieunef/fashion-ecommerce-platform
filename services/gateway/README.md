# Gateway

`TASK:SEC-01` · `REQ:USR-07`, `REQ:XCT-03`.

Gateway là public ingress local của Sprint 1. Chỉ route
`/api/v1/catalog/**` sang `CATALOG_BASE_URL`; không có catch-all route và không public
`/internal/**`. Trước route, filter global với precedence cao nhất bỏ các header do browser
có thể giả mạo: `X-User-Id`, `X-User-Roles`, `X-Actor-Id`, `X-Service-Name`.

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

Actuator expose `health`, `info`, `metrics`; liveness/readiness mặc định do Spring Boot quản
lý. Gateway không có database, migration, Kafka topic hay credential riêng ở S1. Profile
`nacos-compat`, service identity và private networking là evidence Task 5/O02; default local
route cố ý dùng URI cấu hình để cô lập Gateway boundary.
