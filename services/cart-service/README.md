# cart-service

`TASK:CART-01` · `REQ:CART-01/02/04`. Giỏ hàng guest (cookie) và member (Bearer) lưu trong PostgreSQL;
giá và tồn trong giỏ chỉ là preview tham khảo, không giữ hàng, không chốt giá (order quote quyết định).
[Spec](../../docs/superpowers/specs/2026-10-18-cart-01-carts-design.md) ·
[contract](../../contracts/openapi/cart.yaml) ·
[evidence](../../docs/evidence/cart-01-local-2026-10-18.md). Trạng thái: **Review**.

## Chạy và test

```bash
./mvnw -pl services/cart-service -am test        # Docker: PostgreSQL 17.11 Testcontainers; catalog/inventory là stub JDK
bash scripts/local-up.sh && bash scripts/smoke-local.sh   # smoke: "CART-01 guest/member cart PASS"
```

Qua Gateway: `http://localhost:8080/api/v1/carts`. Service không publish port trên host.

## API

| Method | Path | Ghi chú |
|---|---|---|
| POST | `/api/v1/carts` | Member: giỏ ACTIVE (201 khi tạo). Guest: cookie hợp lệ → giỏ đó; không có → giỏ mới + `Set-Cookie` |
| GET | `/api/v1/carts/mine` | Chỉ member |
| GET | `/api/v1/carts/{cart_id}` | Owner; preview `preview_status` OK/UNAVAILABLE/EMPTY |
| POST | `/api/v1/carts/{cart_id}/items` | `Idempotency-Key` bắt buộc; `variant_id`, `quantity` 1–99, `expected_version` |
| PUT | `/api/v1/carts/{cart_id}/items/{item_id}` | Quantity tuyệt đối; tăng thì kiểm lại catalog/tồn |
| DELETE | `/api/v1/carts/{cart_id}/items/{item_id}?expected_version=N` | Xóa dòng |
| DELETE | `/api/v1/carts/{cart_id}?expected_version=N` | Xóa mọi dòng; giỏ vẫn ACTIVE |

Mọi miss (không tồn tại, của người khác, hết hạn, UUID không canonical) → 404 `CART_NOT_FOUND`.
Mọi response `Cache-Control: no-store`. Chi tiết mã lỗi trong contract.

## Danh tính

- Member: access token ADR-21 (`CART_JWT_PUBLIC_KEYS`); Bearer sai → 401 `INVALID_ACCESS_TOKEN`, không fallback cookie.
- Guest: cookie `cart_token` 32 byte base64url; DB chỉ lưu SHA-256; `HttpOnly; Secure; SameSite=Lax;
  Path=/api/v1/carts; Max-Age=2592000`; hết hạn 30 ngày trượt. Token không vào log/body.

## Cấu hình

| Biến | Mặc định | Ý nghĩa |
|---|---|---|
| `CART_DB_URL`, `CART_DB_PASSWORD` | local | runtime role `cart_runtime` |
| `CART_MIGRATION_DB_URL`, `CART_MIGRATION_DB_PASSWORD` | local | Flyway role `cart_migration` |
| `CART_JWT_PUBLIC_KEYS` | — | verifier key member |
| `CART_SERVICE_PRIVATE_KEY`, `CART_SERVICE_PUBLIC_KEY` | trống | ADR-19 caller `cart-service`; local-up sinh và thêm public key vào `CATALOG_INTERNAL_CALLERS`, `INVENTORY_INTERNAL_CALLERS`. Trống → mọi gọi dependency 503 |
| `CART_CATALOG_URL`, `CART_INVENTORY_URL` | `localhost:8081`, `:8083` | internal API |
| `CART_DEPENDENCY_TIMEOUT` | `1s` | mỗi lần gọi catalog/inventory |

## Database

`V001__carts.sql`: `carts`, `cart_items`, `idempotency_requests`. Runtime không DELETE/TRUNCATE `carts`.
Local DB/role tạo bởi `infra/local/postgres-init/04-cart-db.sh` (idempotent, chạy lại trong local-up).

## Health và observability

Readiness: `readinessState,db` (không phụ thuộc catalog/inventory). Log JSON ECS có redaction, trace OTLP
khi đặt `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT`, `/actuator/prometheus` trên port 8084.

## Giới hạn

Chưa có merge guest→member (CART-02), snapshot/cleanup cho order (CART-03), job dọn giỏ guest hết hạn,
Redis cache hay rate limit riêng. Không event Kafka.
