# Nghiệm thu task local — 2026-10-19

## TASK:WEB-01 — storefront browse (bản thô)

Chủ dự án yêu cầu Claude Code nghiệm thu `TASK:WEB-01` sau khi Application CI của PR #28 PASS, với ghi chú: **mới xong phần
thô, chưa hoàn thiện chi tiết UI**. **Không độc lập:** WEB-01 do Claude Code viết. Review gồm một subagent reviewer
(0 Critical, 3 Important đã sửa) và một agent review riêng trên PR #28 (3 finding P2 đã sửa ở `c024084`); quyết định đồng ý
merge của reviewer sau lượt sửa được chủ dự án xác nhận trong phiên làm việc (chưa có comment mới trên PR tại lúc ghi). Không
thay review bởi người khác tác giả. Phạm vi: acceptance ở [10](../delivery/10_backlog.md) dòng WEB-01 + SF-01/02/03 ở
[14](../design/14_frontend_behavior.md), mức local trước G0.

### Bằng chứng chung

- Code trên `dev` `c024084`, PR #28 (chưa merge). Application CI (pull_request) SUCCESS
  ([run 39150569066](https://github.com/trunghieunef/fashion-ecommerce-platform/actions/runs/39150569066)): backend toàn
  reactor 748 test, frontend typecheck/unit/build, contract **68 test** (gồm `test_storefront_fixtures.py`), local stack +
  smoke (`S1-local smoke PASS`), browser smoke Playwright **8 test** (s1-local + web-01, desktop + 360px). Documentation CI SUCCESS.
- Local: Vitest 21 test, typecheck, build PASS; Playwright web-01 6/6 trên stack thật + smoke; mutation guard trang cũ bị bắt.
  Chi tiết: [evidence WEB-01](web-01-local-2026-10-18.md).

### Kết quả theo tiêu chí

| Tiêu chí (10 WEB-01 / 14) | Bằng chứng | Kết quả |
|---|---|---|
| Shell/list/collection/detail (SF-01/02/03) | Routes `/`, `/products`, `/search`, `/collections/:slug`, `/products/:id`; `CollectionPage.test.tsx`, `ListingPage.test.tsx`, `ProductPage.test.tsx`; e2e web-01 | Pass |
| Filter URL, đổi filter reset cursor, không trộn trang cũ | `reads filters from the URL…`, `a filter change resets the cursor…`, `drops a late load-more page…`, `keeps category and brand…`, `keeps tag…`; mutation guard | Pass |
| Detail: chọn variant, out-of-stock/unknown rõ, không cam kết giữ hàng | `asks for a variant…three stock states`, `partial size/colour matrix never gets stuck`; e2e chọn bằng bàn phím | Pass |
| Zoom, nhiều ảnh, size guide | `opens the zoom dialog…`, `every image is reachable…`, `renders the size guide table…` | Pass |
| Responsive VI/EN, `<html lang>` | e2e desktop + 360px không cuộn ngang; `…refetching in English`, fallback caption VI | Pass (mức cơ bản, xem ngoại lệ) |
| Loading/empty/error/retry, 400 không lặp retry, 404 | `shows the empty state…`, `offers a retry…`, `a 400 filter error names the field…`, `unknown collection and unknown route…` | Pass |
| Mock có contract | `tests/contracts/test_storefront_fixtures.py` (8 fixture khớp `catalog.yaml`, đã bắt một fixture sai) | Pass |
| Keyboard, mobile 360px | e2e `detail with contract fixtures` (Space/Arrow trên radio, 360px) | Pass |

**`TASK:WEB-01`: Done (local, trước G0) — bản thô, chưa hoàn thiện chi tiết UI**; còn bước merge PR #28 vào `main` do chủ dự án.

### Ngoại lệ và việc còn lại (không chặn Done bản thô)

- **Chi tiết UI chưa làm** (theo chủ dự án): thẩm mỹ/design system; trên phone: bộ lọc luôn mở chiếm màn đầu (nên thu gọn),
  vùng chạm size/màu ~34px (< 44px khuyến nghị), header chiếm 2–3 dòng, chưa kiểm trực quan/thiết bị thật.
- Đổi ngôn ngữ ở trang chi tiết tải lại sản phẩm (mất lựa chọn); `observed_at` chưa hiển thị; cursor không lưu URL.
- Chưa đo accessibility tự động (axe); content O10 thật chưa có (fixture/demo).
- PLT-02 vẫn In progress (WEB-01 dùng contract đã implement, 08).
- Phase 1A còn WEB-02, ADM-01, QA-01.

## TASK:SHP-01 — shipping-service SELF (phí, quote, shipment)

Chủ dự án yêu cầu Claude Code nghiệm thu `TASK:SHP-01` sau khi Application CI của PR #29 PASS. **Không độc lập:** SHP-01 do
Claude Code viết. Review gồm một subagent reviewer toàn nhánh (0 Critical, 2 Important + Minor đã sửa) và một agent review
riêng trên PR #29 (10 mục: 7 sửa ở `5c673bc`, 3 không sửa có lý do và reviewer đã chấp nhận); re-review tìm thêm lỗi escape
trong `local-up.sh`, đã sửa ở `c1b12ce`. Reviewer **chưa đăng ý kiến đồng ý cho commit cuối** tại lúc ghi; merge do chủ dự
án. Phạm vi: acceptance ở [10](../delivery/10_backlog.md) dòng SHP-01 theo [spec đã duyệt](../superpowers/specs/2026-10-19-shp-01-shipping-design.md), mức local trước G0.

### Bằng chứng chung

- Code trên `dev` `afea3c6`, PR #29 (chưa merge). Application CI (pull_request) SUCCESS
  ([run 39287693309](https://github.com/trunghieunef/fashion-ecommerce-platform/actions/runs/39287693309)): backend toàn
  reactor **779 test** (shipping-service 29), frontend typecheck/unit/build, contract **73 test**, local stack (`local-up`
  với hàm `service_key` mới) + smoke (`SHP-01 shipping estimate PASS`, `S1-local smoke PASS`), browser smoke Playwright 8 test.
  Documentation CI SUCCESS ([run 39287693581](https://github.com/trunghieunef/fashion-ecommerce-platform/actions/runs/39287693581)).
- Local: shipping 29 test, `IdempotencyStoreTest` 7 test, build toàn repo; mutation (khóa chống trùng, kiểm quote, tra
  idempotency quote, rule hỏng → 500, catalog thiếu `found` → 503) đều bị test bắt. Chi tiết: [evidence SHP-01](shp-01-local-2026-10-19.md).

### Kết quả theo tiêu chí

| Tiêu chí (10 SHP-01 / spec) | Bằng chứng | Kết quả |
|---|---|---|
| Shipping rules: FLAT/PROVINCE/WEIGHT, version, audit, OPS quyền `shipping.rules` | `RulesAdminIntegrationTest` (7: tạo/sửa/stale 409, validate config, quyền, idempotency tạo, `active` bắt buộc, audit v0 rule mẫu) | Pass |
| SELF fee theo tỉnh + trọng lượng từ catalog, phí COD rõ | `FeeIntegrationTest` (priority, biên tier, FLAT fallback, `cod_fee` chỉ khi COD, 422/503/400/500); smoke qua Gateway | Pass |
| Quote có expiry, lưu DB, idempotent | `quoteIsStoredAndReplayedByKey`, `quoteReplayDoesNotNeedCatalogOrRules`, `runtimeCannotRewriteQuotes` | Pass |
| Create shipment: một shipment SELF/đơn, từ đúng một quote, outbox `SHIPMENT_CREATED` | `ShipmentIntegrationTest` (11: replay/conflict, song song, quote mismatch/đã dùng, history, outbox, runtime không xóa) | Pass |
| Query: internal theo order (status, tracking, `handed_over_at`, version); OPS list/detail | `ShipmentIntegrationTest`, quyền `shipping.read`, cursor | Pass |
| Route internal không public | `GatewayBoundaryTest.shippingRoutesAreForwardedAndInternalIsNot`; smoke internal → 404 | Pass |
| Mapping địa chỉ O04 | O04 còn mở: bảng phí **mẫu** V002, mã tỉnh theo `user_addresses`, không kiểm danh mục hành chính | **Ngoại lệ** |
| State/handed-over APIs (chuyển trạng thái) | Spec đã duyệt chuyển sang SHP-02; SHP-01 chỉ đọc trạng thái/`handed_over_at` | **Ngoại lệ (SHP-02)** |

**`TASK:SHP-01`: Done (local, trước G0) — với ngoại lệ O04 (phí mẫu) và chuyển trạng thái thuộc SHP-02**; còn bước merge
PR #29 vào `main` do chủ dự án.

### Ngoại lệ và việc còn lại (không chặn Done local)

- **O04** (phí ship/COD thật, vùng SELF, nguồn địa chỉ): phải chốt trước demo G1/go-live; hiện OPS sửa rule qua API.
- Route internal (quote, shipment) chưa có smoke trên stack thật vì order-service chưa có (`SHIPPING_INTERNAL_CALLERS` trống); đã có integration test.
- `SHIPMENT_CREATED` mới kiểm ở outbox row (test tắt relay), chưa kiểm publish Kafka/consumer (ORD).
- Estimate public chưa có rate limit riêng ở Gateway (thêm cùng rate-limit chung trước go-live).
- SHP-02 (bàn giao/giao/COD/cancel), SHP-03 (carrier) chưa làm.
