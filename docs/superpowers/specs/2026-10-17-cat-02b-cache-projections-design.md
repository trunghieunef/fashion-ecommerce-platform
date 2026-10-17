# CAT-02b — cache public read, tồn hiển thị và sold_quantity

`TASK:CAT-02` phần 02b · `REQ:CAT-04`, `REQ:CAT-05`, `REQ:CAT-09` · dependency CAT-01, CAT-02a,
INV-01, PLT-03 (đều Done local). Trạng thái: **bản nháp chờ chủ dự án duyệt**, 2026-10-17.
Chủ dự án giao agent tự chốt mặc định; mọi lựa chọn dưới đây là **quyết định của agent**, ghi lại
ở §8 để duyệt. Parent CAT-02 chỉ Done khi 02a + 02b đạt acceptance ở §7.

## 1. Mục tiêu

- Public read catalog chịu tải browse mà không đổi kết quả đúng: Redis cache-aside cho các route
  public, invalidation bền vững sau mọi thay đổi admin, TTL theo 04 §4 là giới hạn trễ cuối.
- Detail hiển thị tồn theo biến thể kèm thời điểm quan sát, lấy từ event của inventory.
- Sort `BEST_SELLING` dùng `sold_quantity` cập nhật từ `ORDER_COMPLETED`, không cộng hai lần.
- Public response có `Cache-Control` rõ ràng; lỗi không bị cache.

Không làm (non-goals): CDN/edge cache, ETag/304 cho JSON, cache internal quote (`no-store`, giá phải
lấy từ DB), cache admin read, phát `CATALOG_CHANGED` ra Kafka (chưa có consumer ngoài catalog),
giảm `sold_quantity` khi trả hàng (ORD-04/INV-03), search nâng cao, warming cache.

Anti-goals (coi là hỏng dù chạy được): Redis quyết định giá hoặc tồn; admin sửa xong mà public vẫn
trả dữ liệu cũ quá TTL; Redis down làm public read lỗi; event cũ làm tồn hiển thị lùi; replay
`ORDER_COMPLETED` cộng sold lần hai.

## 2. Cache Redis (cache-aside, generation)

**Phạm vi cache:** `GET /catalog/products`, `/catalog/search`, `/catalog/products/{id}` (phần catalog,
không gồm tồn — §3), `/catalog/categories`, `/catalog/brands`, `/catalog/collections`,
`/catalog/collections/{slug}`, `/catalog/size-guides/{category_id}`. Chỉ cache response `200`
phần `data`; `metadata`/trace luôn sinh mới. 4xx/5xx không cache.

**Khóa:** `<FASHION_ENV>:catalog:v1:g<gen>:<route>:<sha256 hex của tham số đã chuẩn hóa>`.
Tham số chuẩn hóa là kết quả sau validate của 02a (giá trị đầu tiên của tham số lặp, `q` đã trim,
locale đã resolve, cursor nguyên văn) theo thứ tự field cố định, cùng quy tắc escape backslash rồi
pipe của 02a. Request không hợp lệ bị 400 trước khi chạm cache.

**Generation:** một counter `<FASHION_ENV>:catalog:gen` (mặc định 0 nếu thiếu). Đọc `gen` **trước**
query DB; ghi cache dưới khóa của `gen` đã đọc. Nhờ vậy reader chậm ghi dữ liệu cũ chỉ vào khóa của
generation cũ, không ai đọc lại. Mọi thay đổi admin tăng `gen`, làm toàn bộ cache public hết hiệu lực.
`ponytail:` một generation toàn cục; chuyển sang generation theo product/collection nếu đo thấy tỉ lệ
miss cao do admin ghi nhiều.

**TTL** (04 §4): detail product 300 s; collection detail, size guide, categories, brands 300 s;
list/search/collection list 30 s. Collection có hiệu lực theo thời gian `[start_at, end_at)` nên có
thể hiện/ẩn trễ tối đa bằng TTL của khóa chứa nó (≤ 300 s cho collection detail); chấp nhận và ghi
vào 03.

**Redis lỗi:** timeout connect/command 200 ms; mọi lỗi Redis (đọc, ghi, đọc `gen`) → bỏ qua cache,
query PostgreSQL như 02a, tăng metric, không trả 5xx. Readiness catalog **không** gồm Redis.
Không retry lệnh Redis trong request. Giá trị cache không giải mã được → coi như miss.

**Credential riêng:** catalog dùng Redis ACL user `catalog` chỉ được truy cập khóa
`<FASHION_ENV>:catalog:*` và lệnh cần thiết (GET/SET/INCR/PING); password riêng
`CATALOG_REDIS_PASSWORD` sinh trong local-up như các secret local khác. Không dùng credential của
user-service.

## 3. Invalidation bền vững

- Mọi thay đổi admin đi qua `AdminCommands.create` và `AdminCommands.update`. Trong cùng transaction
  với thay đổi nghiệp vụ (không phải replay idempotency), enqueue một `background_tasks`
  kind `catalog.cache.invalidate`, `business_key` = UUID mới mỗi transaction (mỗi thay đổi có nghĩa
  vụ riêng, không gộp với task đang RUNNING).
- Worker `@Scheduled` mỗi 1 s (bean có điều kiện, như `MediaConfig`): `claimDue` tối đa 100 task,
  một lệnh `INCR gen`, rồi `finish` tất cả task đã claim. Redis lỗi → không finish, task retry theo
  backoff của `BackgroundTaskRepository`; hết lượt → MANUAL kèm metric. Trong lúc đó TTL vẫn giới hạn
  độ trễ.
- Plan phải rà mọi writer làm đổi public read nằm ngoài `AdminCommands` (vd job media đổi trạng thái
  asset đã attach) và enqueue cùng kind trong transaction của writer đó.
- Consumer tồn kho và sold (§4, §5) **không** enqueue invalidation: tồn không nằm trong cache;
  `sold_quantity` chỉ ảnh hưởng thứ tự `BEST_SELLING` và chấp nhận trễ ≤ 30 s của list.

## 4. Tồn hiển thị từ inventory

- Catalog consume `inventory.events` (group `catalog-service`) qua `InboxListenerSupport` của
  platform-messaging. Chỉ xử lý `INVENTORY_UPDATED`, `INVENTORY_RESTOCKED`; type khác ACK bỏ qua.
- Bảng mới `variant_stock_observations(sku PK, available int ≥ 0, stock_version bigint ≥ 1,
  observed_at timestamptz, updated_at)`. Upsert chỉ khi `stock_version` mới **lớn hơn** bản đang lưu;
  event cũ hoặc lặp không đổi gì (vẫn ACK sau commit).
- Detail product: sau khi lấy phần catalog (từ cache hoặc DB), đọc observation theo danh sách SKU
  trong một query PK, ghép vào response. Phần tồn **không** cache trong Redis.
- Contract (thay đổi tương thích, thêm field): mỗi `variants[]` có `available` (integer|null) và
  `observed_at` (date-time|null). Field top-level `available` = tổng `available` của các variant đã có
  observation, `observed_at` = observation **cũ nhất** trong số đó; cả hai null khi chưa variant nào có
  observation. Tồn chỉ để hiển thị, không giữ hàng (REQ CAT-05); checkout dùng reserve của inventory.

## 5. sold_quantity từ ORDER_COMPLETED

- Thêm `ORDER_COMPLETED` vào `contracts/events/registry.json` (topic `order.events`, partition
  `order_no`, aggregate `order_id`) và `$defs/OrderCompleted` trong `order-events.schema.json` theo 03:
  `order_id`, `order_no`, `user_id` nullable, `order_version`, `items[]` (`order_item`: variant_id, sku,
  quantity), `delivered_at`. Order-service (ORD-03) là producer sau này; 02b chỉ là consumer.
- Catalog consume `order.events` (group `catalog-service`), chỉ `ORDER_COMPLETED`. Trong một
  transaction: inbox theo `event_id`; bảng mới `product_sales_applied(order_id PK, applied_at)` chặn
  cùng order đến với `event_id` khác; cộng `quantity` vào `products.sold_quantity` theo product của
  variant. Không đổi `version`/`updated_at` của product (không phải sửa admin).
- `variant_id` không tồn tại trong catalog → lỗi vĩnh viễn → DLT (`order.events.DLT`), không cộng phần
  nào của order đó. Guest order vẫn tính sold (03).

## 6. HTTP cache header

- Public read `200`: `Cache-Control: public, max-age=30` cho list/search/collection list/categories/
  brands; `public, max-age=5` cho product detail (vì có tồn); `public, max-age=60` cho collection detail
  và size guide. Size guide thêm `Vary: Accept-Language` (locale fallback theo header).
- Mọi lỗi public (400/404/406/429/5xx): `Cache-Control: no-store`. Internal quote giữ `no-store`.
- Gateway chuyển nguyên header; không thêm cache tại Gateway.

## 7. Kiểm thử và acceptance

Integration dùng PostgreSQL, Redis và Kafka thật (Testcontainers):

| Nhóm | Case |
|---|---|
| Cache | Hit trả cùng `data` với DB; metadata/trace mới mỗi request; 4xx không vào Redis |
| Invalidation | Admin publish/unpublish/sửa giá/collection/size guide → request sau thấy dữ liệu mới khi worker chạy; replay idempotency không enqueue; Redis down khi worker chạy → task giữ, Redis lên lại → `gen` tăng |
| Race | Reader đọc DB cũ, writer commit + bump, reader ghi cache sau → request kế tiếp không thấy dữ liệu cũ |
| Redis down | Mọi public read vẫn 200 từ DB; readiness UP |
| Tồn | Event mới cập nhật; event `stock_version` cũ/lặp không lùi; variant chưa có observation → null; top-level sum/oldest |
| Sold | Một order cộng đúng theo product; replay cùng `event_id` và `event_id` khác cùng `order_id` không cộng lại; variant lạ → DLT, không cộng phần nào; `BEST_SELLING` đổi thứ tự sau TTL list |
| Header | Từng route đúng `Cache-Control`; lỗi `no-store`; size guide có `Vary` |

Thêm: contract test registry/schema `ORDER_COMPLETED` và field tồn mới trong `catalog.yaml`; mutation
các guard chính (so `stock_version`, dedupe `order_id`, đọc `gen` trước DB, enqueue trong transaction)
phải bị test bắt; full reactor verify, smoke S1-local (thêm bước: admin sửa → public thấy đổi) và CI.

CAT-02 Done khi 02a (đã đạt) + các mục trên pass. Consumer sold được nghiệm thu bằng event tổng hợp
đúng contract; E2E với order thật kiểm lại ở ORD-03.

## 8. Quyết định của agent (cần chủ dự án duyệt)

1. Generation toàn cục thay vì xóa từng khóa (đơn giản, an toàn với race; đánh đổi miss rate).
2. Không cache tồn; tồn đọc projection PostgreSQL mỗi request detail.
3. Invalidation qua `background_tasks` nội bộ, chưa phát `CATALOG_CHANGED` ra Kafka.
4. Thêm field tồn theo variant; top-level = tổng/oldest để giữ tương thích.
5. Tự định nghĩa contract `ORDER_COMPLETED` từ mô tả 03 trước khi có order-service.
6. Redis ACL user riêng cho catalog trên cùng instance local.
7. Header TTL: list 30 s, detail 5 s, collection/size guide 60 s; Redis TTL theo 04 §4.

## 9. Tài liệu đồng bộ khi hiện thực

03 (§2.2 header/tồn, §4 event ORDER_COMPLETED), 04 §4 (cache catalog thực tế), 05 (bảng
`variant_stock_observations`, `product_sales_applied`), 06 (luồng browse/invalidation), 08 (§8),
10 (CAT-02), `catalog.yaml`, registry/schema, catalog README (Redis, topics, metric), local-up/compose
(ACL catalog, topic `order.events`, `order.events.DLT`, `inventory.events.DLT`), evidence/handoff CAT-02b.
