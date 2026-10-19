# SHP-01 — shipping-service: SELF fee rules, quote, shipment create/query

`TASK:SHP-01` · `REQ:SHP-01`, `REQ:SHP-04` (phần dữ liệu) · dependency PLT-03, USR-02, CAT-02 (đều Done local).
Trạng thái: **đã duyệt** 2026-10-19. Chủ dự án giao agent tự chốt mặc định; lựa chọn của agent ghi ở §9.
O04 (phí ship/COD thật, vùng SELF, nguồn địa chỉ, carrier) **vẫn mở**: SHP-01 dùng bảng phí mẫu (fixture có nhãn), OPS sửa
được qua API. SHP-02 (SELF evidence events, bàn giao/giao/thu COD, cancel tombstone), carrier adapter (SHP-03) nằm ngoài.

## 1. Mục tiêu

- Tính phí ship SELF từ server theo **vùng (tỉnh) + tổng trọng lượng** lấy từ catalog, kèm phí COD rõ ràng.
- Order (ORD-01) xin **quote có hạn** (`quote_id`, `expires_at`) để snapshot vào đơn; storefront có **estimate** tham khảo.
- Order tạo **một shipment SELF cho một đơn** (idempotent), đọc lại trạng thái/`handed_over_at`; OPS xem danh sách/chi tiết.
- OPS quản lý rule phí (tạo/sửa/bật tắt) có version và audit.

Không làm: chuyển trạng thái sau CREATED (bàn giao/giao/thất bại/hoàn — SHP-02), thu/nộp COD, cancel/tombstone, retry UNKNOWN
với carrier (SELF không có UNKNOWN), webhook, UI (ADM/WEB).

Anti-goals: client/order tự tính phí; hai vận đơn cho một đơn; phí thay đổi sau khi quote đã cấp; đọc trọng lượng từ client;
route internal public; dữ liệu địa chỉ khách vào log.

## 2. Service và hạ tầng

- `services/shipping-service` (Java 21, Spring Boot 4.0, JdbcClient, Flyway), port **8085**, DB `shipping` (role
  `shipping_migration`/`shipping_runtime`, init idempotent như cart). platform-durability (idempotency, outbox),
  platform-security (Access token OPS, service token), platform-messaging (outbox relay Kafka như inventory).
- Gateway: `/api/v1/shipping/**` (public estimate) và `/admin/api/v1/shipping/**` (OPS) → shipping; `/internal` không route.
- Catalog thêm caller `shipping-service` vào allowlist quote (`CATALOG_INTERNAL_CALLERS`, local-up sinh key như cart).

## 3. Schema (V001)

Theo 05 §11.1, chỉ phần cần cho SHP-01:

- `shipping_rules(id, name, rule_type FLAT|PROVINCE|WEIGHT, priority int, config_json jsonb, active bool, version bigint,
  created_at, updated_at)`; index `(active, priority)`.
- `shipping_quotes(id, input_hash char(64), province_code, weight_grams int>0, cod bool, fee bigint≥0, cod_fee bigint≥0,
  rule_id, rule_version, carrier='SELF', expires_at, created_at)` — **bảng mới so với 05**: order snapshot `quote_id`
  (05 §7 `shipping_quote`), shipping kiểm lại khi tạo shipment.
- `shipments` theo 05 (bỏ trống các cột carrier/retry chưa dùng), `shipment_status_history` theo 05.
- `idempotency_requests`, `outbox_events` (bản sao DDL platform-durability). Runtime không DELETE `shipments`/history/quotes.

## 4. Rule phí

`config_json` theo `rule_type` (validate khi ghi, lỗi 400 theo field):

| rule_type | config | Khớp khi |
|---|---|---|
| FLAT | `{fee, cod_fee}` | luôn khớp (fallback, priority cao nhất số) |
| PROVINCE | `{provinces:[code…], fee, cod_fee}` | `province_code` thuộc danh sách |
| WEIGHT | `{provinces:[code…]?, tiers:[{max_weight_grams|null, fee}], cod_fee}` | (tỉnh khớp nếu có danh sách) và tier đầu tiên `weight ≤ max` (null = không giới hạn) |

Chọn rule ACTIVE có `priority` nhỏ nhất mà khớp; không rule nào khớp → 422 `NO_SHIPPING_RULE`. Tiền là số nguyên VND ≥ 0.
`cod_fee` chỉ cộng khi `cod=true`. Seed mẫu V002 (đánh dấu `(mẫu)`; chỉnh khi triển khai, review SHP-01 #8): WEIGHT HN/HCM
(`01`,`79`) ≤3 kg 25.000 / >3 kg 40.000, COD 10.000 (priority 10); WEIGHT toàn quốc ≤1 kg 30.000 / ≤3 kg 45.000 / >3 kg 70.000,
COD 15.000 (priority 50); FLAT 35.000 / COD 15.000 (priority 1000). Mã tỉnh là chuỗi `province_code` như `user_addresses` (không kiểm danh mục hành chính — O04).

## 5. API

| Method / path | Ai | Hành vi |
|---|---|---|
| `POST /api/v1/shipping/fee/estimate` | Public | `{province_code, items:[{variant_id, quantity}], cod}` → `{fee, cod_fee, carrier, weight_grams, estimate: true, expires_at (+1 h)}`; không lưu, không quote_id |
| `POST /internal/api/v1/shipping/quotes` | `order-service` | Cùng input + `Idempotency-Key` → lưu `shipping_quotes`, trả `{quote_id, fee, cod_fee, carrier, weight_grams, rule_id, rule_version, expires_at (+15 phút)}` |
| `POST /internal/api/v1/shipping/shipments` | `order-service` | `{order_id, order_no, quote_id, to_address{recipient_name, phone, province_code, district_code?, ward_code, address_line}, cod_amount}` + key → shipment SELF |
| `GET /internal/api/v1/shipping/shipments/by-order/{order_id}` | `order-service` | `{shipment_id, status, carrier, tracking_code, handed_over_at, version}` |
| `GET /admin/api/v1/shipping/shipments`, `/{id}` | `shipping.read` | Danh sách (filter status, cursor) / chi tiết + history |
| `GET,POST /admin/api/v1/shipping/rules`, `PUT /…/{id}` | `shipping.rules` | Tạo/sửa (expected_version) / bật-tắt; audit actor + reason |

- Trọng lượng: shipping gọi catalog quote (service token) cho các variant; variant không found/inactive → 422
  `VARIANT_NOT_AVAILABLE`; `weight = Σ weight_grams × quantity` (Math.addExact/multiplyExact). Catalog lỗi → 503.
  1–100 item, quantity 1–99.
- Create shipment: quote phải tồn tại, chưa hết hạn **tại lúc order tạo đơn** — order có thể tạo shipment muộn (PACKING),
  nên chỉ kiểm quote tồn tại và `fee` lấy từ quote (không kiểm expiry ở đây). Quote phải khớp đơn:
  `province_code` của quote = `to_address.province_code` và `quote.cod = (cod_amount > 0)`, sai → 409 `QUOTE_MISMATCH`;
  một quote chỉ dùng cho một shipment (UNIQUE `quote_id` trên `shipments`). Một TX: insert PENDING → SELF xác nhận ngay →
  CREATED, `tracking_code = SELF-<order_no>`, history 2 dòng, outbox `SHIPMENT_CREATED`. UNIQUE `order_id`: retry cùng key/body
  → 200 bản cũ; cùng order, body khác → 409 `SHIPMENT_CONFLICT`. `handed_over_at` null (SHP-02 set).
- Địa chỉ chỉ lưu trong `to_address` (jsonb); không log, response OPS trả đủ (quyền `shipping.read`), internal by-order không
  trả địa chỉ. `from_address` = kho mặc định cấu hình (`SHIPPING_FROM_ADDRESS`, placeholder local).

## 6. Event

`contracts/events/shipping-events.schema.json` + registry: `SHIPMENT_CREATED` (topic `shipping.events`, key `order_no`,
payload theo 03 §5.3: order_id, order_no, shipment_id, carrier, tracking_code, status, handed_over_at, provider_occurred_at,
shipment_version). Relay platform-messaging. Chưa có consumer (order là ORD-01+).

## 7. Kiểm thử

Integration PostgreSQL thật; catalog là HTTP stub; Kafka relay theo pattern inventory.

| Nhóm | Case |
|---|---|
| Rule | chọn theo priority; PROVINCE khớp/không; WEIGHT tier biên (= max, vượt, null); FLAT fallback; không khớp → 422; cod_fee chỉ khi cod |
| Weight | từ catalog × quantity; variant inactive/không tồn tại → 422; catalog lỗi → 503; tràn số → 400 |
| Quote | lưu đúng rule/version, expires +15'; retry cùng key → cùng quote; estimate không lưu |
| Shipment | tạo CREATED + history + outbox; retry cùng key → cùng shipment; order khác body → 409; quote không tồn tại → 404; hai create song song cùng order → một shipment |
| Auth | public estimate không cần token; internal chỉ order-service (401/403 trước body); admin cần `shipping.rules`/`shipping.read` |
| Rule admin | tạo/sửa version (409 stale), config sai theo type → 400 field; audit |
| Gateway | public/admin route, internal 404 |

Smoke local: estimate qua Gateway với rule mẫu. Mutation các guard chính. Verify theo module (tắt compose), contract, docs, CI.

## 8. Tài liệu

`services/shipping-service/README.md`, `contracts/openapi/shipping.yaml` (+ internal), event schema/registry, 03 (§3/§4
shipping implemented), 05 (`shipping_quotes`), 06 §9, 08 (quyết định), 10, 12 (lệnh), compose/local-up/.env.example, evidence.

## 9. Quyết định của agent (cần duyệt)

1. Service mới `shipping-service`, port 8085, DB `shipping`; chỉ carrier **SELF** trong SHP-01.
2. **O04 vẫn mở**: bảng phí mẫu seed V002 có nhãn "(mẫu)", OPS sửa qua API; mã tỉnh không kiểm danh mục hành chính.
3. Rule: ACTIVE, priority nhỏ nhất khớp thắng; FLAT là fallback; không khớp → 422 (không tự đặt phí 0).
4. Trọng lượng shipping tự lấy từ catalog quote (không tin client/order) → thêm `shipping-service` vào allowlist catalog.
5. Estimate public nhận `items` (không cần giỏ/actor), hết hạn 1 h, không lưu; **khác 03** (03 ghi cart/version) — ghi 08.
6. Quote internal lưu bảng `shipping_quotes` (bảng mới so với 05), hạn 15 phút; shipment lấy `fee` từ quote.
7. SELF tạo shipment PENDING → CREATED trong một TX (không có bước UNKNOWN), tracking `SELF-<order_no>`; bàn giao/giao là SHP-02.
8. Có event `SHIPMENT_CREATED` qua outbox ngay từ SHP-01 (schema + registry mới).
