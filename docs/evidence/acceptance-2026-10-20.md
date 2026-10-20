# Nghiệm thu task local — 2026-10-20

## TASK:SHP-02 — shipping SELF events, COD, cancel

Chủ dự án yêu cầu Claude Code nghiệm thu `TASK:SHP-02` sau khi merge PR #30. **Không độc lập:** SHP-02 do Claude Code viết.
Review gồm một subagent reviewer toàn nhánh (Critical/Important đã sửa ở `96c00f9`) và một agent review riêng trên PR #30
(1 Important + 4 Minor, sửa ở `eb0b7c1`); re-review trên PR kết luận **đủ điều kiện merge** và nêu một Minor không chặn,
đã sửa sau merge (dưới). Merge do chủ dự án. Phạm vi: acceptance ở [10](../delivery/10_backlog.md) dòng SHP-02 theo
[spec đã duyệt](../superpowers/specs/2026-10-20-shp-02-self-events-cancel-design.md), mức local trước G0.

### Bằng chứng chung

- Code trên `dev` `eb0b7c1`, PR #30 đã merge `main` `03b3aa6`. Application CI (pull_request) SUCCESS
  ([run 39297751605](https://github.com/trunghieunef/fashion-ecommerce-platform/actions/runs/39297751605)): backend toàn
  reactor **795 test** (shipping-service 45), frontend typecheck/unit 21/build, contract **74 test**, local stack + smoke
  (`SHP-02 self-event route PASS`, `SHP-01 shipping estimate PASS`, `S1-local smoke PASS`), browser smoke Playwright 8 test.
  Documentation CI SUCCESS ([run 39297751607](https://github.com/trunghieunef/fashion-ecommerce-platform/actions/runs/39297751607)).
- Local: shipping 45 test; mutation (nhánh CANCELLED giữ bằng chứng, thứ tự `occurred_at`) bị test bắt. Chi tiết:
  [evidence SHP-02](shp-02-local-2026-10-20.md).

### Kết quả theo tiêu chí

| Tiêu chí (10 SHP-02 / spec) | Bằng chứng | Kết quả |
|---|---|---|
| SELF evidence events (gán shipper, bàn giao, giao, thất bại, giao lại, hoàn) có actor/evidence, version, idempotency | `SelfEventIntegrationTest` (bảng cạnh, parse strict, VERSION_CONFLICT, INVALID_TRANSITION, replay), quyền `shipping.self_event`; smoke route qua Gateway | Pass |
| Không SHIPPING ngay khi CREATED: `handed_over_at` chỉ đặt khi bàn giao | Cạnh HANDED_OVER chỉ từ PICKING; CHECK `shipments_handed_over_check` (V004) | Pass |
| COD collected/remitted payload (event tài chính riêng) | `fullSelfPathWithCodAndEvents`: số tiền phải khớp, một lần; payload khớp tập field schema; fixture + contract test | Pass |
| Cancel tombstone (T15–T17): hủy trước bàn giao, hủy-trước-tạo, 409 sau bàn giao | `ShipmentCancelIntegrationTest` (6, gồm song song cancel/create và cancel/bàn giao) | Pass |
| Bằng chứng vật lý sau hủy không bị mất (06 §17.2) | History `applied=false`, 409 `CANCELLED_EVIDENCE_RECORDED`; mốc giờ cũ hơn vẫn được ghi (sửa sau merge) | Pass |
| Chuyển MANUAL + incident khi có bằng chứng trên CANCELLED | Chưa làm: câu hỏi mở cho chủ dự án ở [08](../design/08_decisions.md) | **Ngoại lệ (chờ quyết định)** |

**`TASK:SHP-02`: Done (local, trước G0) — ngoại lệ MANUAL + incident chờ quyết định ở 08**; chủ dự án đã merge PR #30 vào
`main` (`03b3aa6`, 2026-10-20), CI trên `eb0b7c1` PASS trước merge.

### Sửa sau merge

Re-review: trên CANCELLED, `occurred_at` bị kiểm thứ tự nên bằng chứng có mốc sớm hơn dòng đã ghi bị 400. Đã bỏ kiểm thứ tự
trên CANCELLED (giữ 60 giây và mốc tạo), thêm assert vào `evidenceOnACancelledShipmentIsKeptNotDropped`; shipping 45 test PASS
local. Sửa này đi cùng PR kế tiếp từ `dev`.

### Ngoại lệ và việc còn lại (không chặn Done local)

- Quyết định MANUAL + incident và báo order khi có bằng chứng trên CANCELLED: chốt trước G1/G2 (liên quan kho và tiền COD).
- Consumer của `SHIPMENT_STATUS_UPDATED`/`COD_*` (ORD-03/04, PAY-01) và route internal cancel từ order-service chưa có;
  chỉ có integration test và outbox row.
- Upload ảnh bằng chứng, nộp COD nhiều đợt, `NOTIFY_SHIPMENT_STATUS` (NOT-01), carrier (SHP-03) chưa làm.
- O04 (phí mẫu) vẫn mở từ SHP-01.
