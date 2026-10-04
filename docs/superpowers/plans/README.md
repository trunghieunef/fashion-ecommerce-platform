# Kế hoạch triển khai tới MVP

Bộ kế hoạch này chuyển baseline B1 thành các gói triển khai tuần tự. Mỗi gói tạo ra phần mềm chạy được và có gate nghiệm thu riêng; trạng thái của kế hoạch không thay trạng thái task trong [backlog](../../delivery/10_backlog.md).

| Thứ tự | Kế hoạch | Kết quả có thể nghiệm thu | Gate |
|---|---|---|---|
| 1 | [Sprint 1 — local foundation](2026-09-19-sprint-1-local-foundation.md) | Fresh clone chạy storefront shell → Gateway → catalog sample → PostgreSQL; version/contract/security proof | S1-local |
| — | [Handoff Sprint 1 — 2026-09-22](2026-09-22-s1-local-handoff.md) | Trạng thái checkout, evidence đã có và thứ tự tiếp tục | Không thay gate |
| — | [Handoff sang máy mới — 2026-10-02](2026-10-02-s1-local-machine-handoff.md) | Toolchain, checklist fresh-clone, evidence/CI và phần cần nghiệm thu trên môi trường mới | Không thay gate |
| — | [Handoff PLT-02 — 2026-10-04](2026-10-04-plt-02-handoff.md) | Contract core đã có, PR #3, việc còn lại và ý định PLT-03 | Không thay gate |
| — | [Handoff Phase 0 — cuối ngày 2026-10-04](2026-10-04-phase-0-handoff.md) | Trạng thái PLT-02/03/04, SEC-01, O06; việc cần chủ dự án và bước tiếp theo | Không thay gate |
| — | [Handoff Phase 0/1A — cuối ngày 2026-10-05](2026-10-05-phase-1a-handoff.md) | PR #9 mở (USR-02a), trạng thái USR-01/02, PLT-04/05, bước tiếp USR-02b → USR-01b | Không thay gate |
| 2 | [Phase 0 — platform và AWS staging](2026-09-19-phase-0-platform-staging.md) | Contract core, durable primitives, CI ứng dụng, AWS staging một service mẫu, observability và rollback | G0 |
| 3 | [Phase 1A — commerce core](2026-09-19-phase-1a-commerce-core.md) | Identity, catalog, inventory, cart và UI cơ bản chạy bằng contract thật | Integration checkpoint |
| 4 | [Phase 1B — COD vertical slice](2026-09-19-phase-1b-cod-vertical-slice.md) | Guest/member checkout COD, SELF fulfillment, notification, admin và settlement | G1 |
| 5 | [Phase 1C — online-payment MVP](2026-09-19-phase-1c-online-payment-mvp.md) | VNPay + MoMo sandbox, refund/recovery/security/load/restore/UAT | G2 MVP |

## Cách dùng

1. Chỉ bắt đầu kế hoạch sau khi gate và dependency của kế hoạch trước có evidence được reviewer chấp nhận.
2. Trong từng task, dùng `TASK:<id>` và REQ tương ứng; không coi task con là parent task đã Done nếu acceptance còn thiếu.
3. Mỗi PR phải đồng bộ code, contract/schema, test và tài liệu chủ quản. Nếu không ảnh hưởng tài liệu, handoff ghi `Docs: N/A` kèm lý do.
4. Các bước commit trong kế hoạch chỉ được chạy khi chủ dự án đã cho phép commit; nếu chưa có quyền, dừng ở checkpoint đã test và bàn giao diff.
5. Không triển khai Phase 2: promotion, OTP/social, carrier ngoài, review/wishlist/restock và marketing automation không thuộc G2 MVP.

## Phân tuyến công việc

| Gói | Lead / phối hợp | Chuỗi bắt buộc | Việc có thể song song sau dependency |
|---|---|---|---|
| S1-local | TL + DEVOPS / BE + FE | toolchain → catalog sample → Gateway → storefront → fresh-clone proof | Contract endpoint và security test có thể làm cùng storefront sau khi route ổn định |
| Phase 0/G0 | DEVOPS + TL / BE + QA | contract/primitives/security → image/OIDC → staging → recovery proof | Contract, durability và security có thể chia ba nhánh; AWS chỉ bắt đầu sau S1-local và O01 approval |
| Phase 1A | BE leads theo service / FE + QA | catalog event → stock → reservation; user → cart merge; API → UI | User/cart và catalog/inventory là hai tuyến độc lập cho tới integration checkpoint |
| Phase 1B/G1 | BE Order lead / FE + OPS + FINANCE | SELF + COD core → quote/saga → fulfillment/cancel → UI/UAT | Notification có thể làm song song sau user + platform durability |
| Phase 1C/G2 | BE Payment lead / QA + DEVOPS + OPS + FINANCE | provider adapters → refund/order recovery → UI/regression → load/restore → go/no-go | VNPay và MoMo adapters có thể làm song song trên payment core; load và restore bắt đầu sau QA-02 ổn định |

Không gắn các gói này với ngày lịch khi O08 chưa khóa capacity. Estimate nguồn vẫn là Phase 0 11–21 person-day và Phase 1 70–122 person-day trong 09/10; nhóm phải re-estimate tại planning dựa trên số người thực tế, thời gian chờ provider và reviewer.

## Chuỗi gate

```text
S1-local
  -> O01/O02 inputs + Phase 0 platform
  -> G0 staging
  -> commerce core integrated
  -> G1 COD/SELF
  -> provider sandbox + recovery + QA/OPS
  -> G2 MVP release decision
```

G2 là khả năng phát hành sau review, không tự cho phép deploy production. AWS production/HA là quyết định riêng sau sizing, cost, security và go/no-go.
