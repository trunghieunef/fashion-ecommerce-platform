# shipping-service

`TASK:SHP-01` · `REQ:SHP-01`, `REQ:SHP-04` (phần dữ liệu). SELF fee rules theo tỉnh + trọng lượng, estimate public,
quote cho order, một shipment SELF cho một đơn. `TASK:SHP-02` · `REQ:SHP-02/04/06`: sự kiện SELF có bằng chứng, COD
thu/nộp, hủy và tombstone ([spec](../../docs/superpowers/specs/2026-10-20-shp-02-self-events-cancel-design.md)). [Spec](../../docs/superpowers/specs/2026-10-19-shp-01-shipping-design.md) ·
[contract](../../contracts/openapi/shipping.yaml) · [internal](../../contracts/openapi/shipping-internal.yaml) ·
[evidence](../../docs/evidence/shp-01-local-2026-10-19.md).

**O04 còn mở**: bảng phí trong `V002__sample_rules.sql` là **mẫu** (tên có "(mẫu)"), OPS sửa qua API rules. Mã tỉnh dùng đúng
`province_code` như `user_addresses`, không kiểm danh mục hành chính. Chỉ carrier SELF; bàn giao/giao/thu COD/cancel là SHP-02.

## Chạy và test

```bash
./mvnw -pl services/shipping-service -am test   # Docker: PostgreSQL 17.11 Testcontainers; catalog là stub JDK; relay tắt
bash scripts/local-up.sh && bash scripts/smoke-local.sh   # smoke: "SHP-01 shipping estimate PASS"
```

Port 8085 (không publish ra host). Qua Gateway: `/api/v1/shipping/**`, `/admin/api/v1/shipping/**`; `/internal` không route.

## API

| Method / path | Ai | Ghi chú |
|---|---|---|
| `POST /api/v1/shipping/fee/estimate` | Public | `{province_code, cod, items[{variant_id, quantity}]}` → fee/cod_fee/weight; hết hạn 1 h; không lưu |
| `POST /internal/api/v1/shipping/quotes` | `order-service` | Cùng input + `Idempotency-Key` → `quote_id` lưu DB, rule id/version, hạn 15 phút |
| `POST /internal/api/v1/shipping/shipments` | `order-service` | order_id/order_no/quote_id/cod_amount/to_address + key → shipment SELF `CREATED` |
| `GET /internal/api/v1/shipping/shipments/by-order/{order_id}` | `order-service` | Trạng thái, tracking, `handed_over_at`, version (không trả địa chỉ) |
| `GET,POST /admin/api/v1/shipping/rules`, `PUT /…/{id}` | `shipping.rules` | Rule FLAT/PROVINCE/WEIGHT; POST cần `Idempotency-Key` (retry cùng user → rule cũ); PUT cần `expected_version` và `active`; `reason` bắt buộc, audit |
| `GET /admin/api/v1/shipping/shipments`, `/{id}` | `shipping.read` | Danh sách (status, cursor) / chi tiết có địa chỉ + history (kèm evidence) |
| `POST /admin/api/v1/shipping/shipments/{id}/self-events` | `shipping.self_event` | SHP-02: `event_type`, `expected_version`, `Idempotency-Key`; trả chi tiết |
| `POST /internal/api/v1/shipping/shipments/by-order/{order_id}/cancel` | `order-service` | SHP-02: snapshot create + `reason`, key → shipment CANCELLED / tombstone; sau bàn giao 409 |

- **Phí**: trọng lượng = Σ `weight_grams` (catalog quote, service token `shipping-service`) × quantity; rule ACTIVE có `priority`
  nhỏ nhất khớp thắng (FLAT luôn khớp, PROVINCE theo danh sách tỉnh, WEIGHT theo tier `weight ≤ max_weight_grams`, null = không
  giới hạn); `cod_fee` chỉ khi `cod=true`. Không khớp → 422 `NO_SHIPPING_RULE`; variant không bán → 422 `VARIANT_NOT_AVAILABLE`;
  catalog lỗi → 503; tràn số → 400 `WEIGHT_OUT_OF_RANGE`.
- **Shipment**: một TX — advisory lock theo `quote_id` → PENDING → CREATED (`SELF-<order_no>`), 2 dòng history, outbox
  `SHIPMENT_CREATED` (`shipping.events`, key `order_no`). Cùng order + cùng body → shipment cũ (mọi key); khác body → 409
  `SHIPMENT_CONFLICT`; quote khác tỉnh/COD → 409 `QUOTE_MISMATCH`; quote của đơn khác → 409 `QUOTE_ALREADY_USED`.
  Hạn quote do order kiểm khi tạo đơn, không kiểm lại lúc tạo shipment. Đã hủy/tombstone → 409 `SHIPMENT_CANCELLED`.
- **SELF events (SHP-02)**: ASSIGN CREATED/PICKING→PICKING; HANDED_OVER PICKING→DELIVERING (đặt `handed_over_at`); DELIVERED;
  DELIVERY_FAILED→FAILED; REDELIVER FAILED→DELIVERING; RETURN_STARTED FAILED→RETURNING; RETURNED. COD_COLLECTED (DELIVERED,
  đúng `cod_amount`, một lần) / COD_REMITTED (đủ phần chưa nộp). Một TX: khóa hàng, version, history `self:<id>` (actor,
  evidence, không địa chỉ), outbox `SHIPMENT_STATUS_UPDATED` / `COD_COLLECTED` / `COD_REMITTED`. Cạnh sai → 409 `INVALID_TRANSITION`.
- **Cancel (SHP-02)**: cùng advisory lock quote với create. Chưa có shipment → tombstone CANCELLED; CREATED/PICKING → CANCELLED;
  đã bàn giao → 409 `SHIPMENT_HANDED_OVER` (order đi return, không cộng kho).

## Cấu hình

| Biến | Mặc định | Ý nghĩa |
|---|---|---|
| `SHIPPING_DB_URL`, `SHIPPING_DB_PASSWORD` | local | runtime role `shipping_runtime` |
| `SHIPPING_MIGRATION_DB_URL`, `SHIPPING_MIGRATION_DB_PASSWORD` | local | Flyway role `shipping_migration` |
| `SHIPPING_JWT_PUBLIC_KEYS` | — | verifier access token OPS |
| `SHIPPING_INTERNAL_CALLERS` | trống | `order-service:<base64 X.509>`; trống → mọi call internal 401 (order-service chưa có) |
| `SHIPPING_SERVICE_PRIVATE_KEY`, `SHIPPING_SERVICE_PUBLIC_KEY` | trống | caller `shipping-service` cho catalog quote; local-up sinh và thêm vào `CATALOG_INTERNAL_CALLERS` |
| `SHIPPING_CATALOG_URL`, `SHIPPING_DEPENDENCY_TIMEOUT` | `localhost:8081`, `1s` | catalog internal |
| `SHIPPING_ESTIMATE_TTL`, `SHIPPING_QUOTE_TTL` | `1h`, `15m` | hạn estimate/quote |
| `SHIPPING_FROM_ADDRESS` | kho mẫu (JSON) | địa chỉ lấy hàng SELF, placeholder local (O04) |
| `SHIPPING_RELAY_ENABLED`, `SPRING_KAFKA_BOOTSTRAP_SERVERS` | `true`, `localhost:19092` | outbox relay (platform-messaging) |

## Database

`V004__handed_over_check.sql`: CHECK trạng thái từ DELIVERING trở đi phải có `handed_over_at`. `V001__shipping.sql`: `shipping_rules`, `shipping_rule_changes` (audit), `shipping_quotes` (mới so với 05), `shipments`,
`shipment_status_history`, `outbox_events`, `idempotency_requests`. Runtime không DELETE/TRUNCATE shipments/history/quotes/rules,
không UPDATE history/quotes/audit. `V002__sample_rules.sql`: 3 rule mẫu; `V003__sample_rules_audit.sql`: audit version 0 của chúng. Local DB/role: `infra/local/postgres-init/05-shipping-db.sh`.

## Health, observability, giới hạn

Readiness `readinessState,db`; log JSON ECS có redaction (địa chỉ không vào log); trace OTLP khi đặt endpoint; `/actuator/prometheus`.
Chưa có: carrier thật, webhook, UNKNOWN/retry (SHP-03); consumer các event shipping (ORD/PAY); `NOTIFY_SHIPMENT_STATUS` (NOT-01);
upload ảnh bằng chứng (`evidence_ref` là mã tham chiếu); nộp COD nhiều đợt.
Estimate public không cần đăng nhập và gọi catalog (≤ 100 variant/request); chưa có rate limit riêng ở Gateway — thêm cùng
rate-limit chung (03 §1) trước go-live. Mỗi lần tính phí kiểm lại config của mọi rule ACTIVE; một rule lỗi (chỉ có thể do
migration, API đã validate) làm estimate/quote trả 500 `INTERNAL` (lỗi server, có log rule id) — không bỏ qua để tránh tính nhầm
giá của rule khác. Catalog trả `found` thiếu/sai kiểu → 503, không phải 422.
