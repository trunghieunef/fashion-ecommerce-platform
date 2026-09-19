# Cẩm nang tài liệu dự án

Baseline B1 · 2026-09-19 · Đồng bộ để phân công; chưa nghiệm thu triển khai.

## 1. Danh mục và nguồn sự thật

| Tài liệu | Nội dung chịu trách nhiệm | Người duy trì theo vai trò |
|---|---|---|
| [01 Brief](design/01_brief.md) | Mục tiêu, ràng buộc, ranh giới sản phẩm | PO |
| [02 PRD](design/02_prd.md) | Mã yêu cầu, ưu tiên, phase, điều kiện nghiệp vụ | PO + TL |
| [03 Interfaces](design/03_interfaces.md) | Route, auth, lỗi, dữ liệu API và event | TL + chủ service |
| [04 Architecture](design/04_architecture.md) | Ranh giới service, dữ liệu, topology và resilience | TL + DEVOPS |
| [05 Database](design/05_database_design.md) | Bảng, kiểu dữ liệu, constraint và index | BE + TL |
| [06 Service flows](design/06_service_flows.md) | Transaction, state machine, retry và compensation | BE + TL |
| [07 Diagrams](design/07_diagrams.md) | Các góc nhìn trực quan của 03–06 | TL |
| [08 Decisions](design/08_decisions.md) | D01–D12, ADR, vấn đề mở và lịch sử đồng bộ | PO + TL |
| [09 Delivery plan](delivery/09_delivery_plan.md) | Vai trò, milestone, phụ thuộc, DoR/DoD | TL + PO |
| [10 Backlog](delivery/10_backlog.md) | Work package có đầu ra, dependency và acceptance | Người nhận task |
| [11 Test strategy](quality/11_test_strategy.md) | Test, traceability, dữ liệu và release gates | QA + BE + FE |
| [12 Engineering guide](engineering/12_engineering_guide.md) | Onboarding, cấu trúc dự kiến, CI và quy trình PR | TL + DEVOPS |
| [13 Operations & security](operations/13_operations_security.md) | Quyền, PII, monitoring, runbook, backup và go-live | DEVOPS + TL + FINANCE |
| [14 Frontend behavior](design/14_frontend_behavior.md) | Màn hình, hành vi, quyền thao tác và trạng thái UI | FE + PO |
| [15 Integrations](engineering/15_integrations.md) | Hợp đồng provider, sandbox và điều kiện bật thật | BE + DEVOPS |

PO = chủ sản phẩm; TL = tech lead; BE = backend; FE = frontend; QA = trách nhiệm kiểm thử; DEVOPS = trách nhiệm nền tảng. Đây là vai trò, chưa phải tên người được giao việc.

Mỗi loại dữ liệu có một tài liệu chủ quản như bảng trên. Khi xung đột, sửa đồng thời các tài liệu liên quan; không tự lấy file có ngày mới hơn làm phê duyệt. D01–D12 là giả định của baseline để nhóm estimate; PO xác nhận trước khi khóa nghiệm thu tính năng liên quan.

## 2. Lộ trình đọc theo vai trò

- Mọi thành viên: 01 → 02 → 08 → 09 → task được giao trong 10.
- Backend: thêm 03 → 04 → phần service của 05–06 → 11 → 12 → 15.
- Frontend: thêm 03 → 14 → checkout/COD/hủy ở 06 → 11.
- QA: thêm 03, invariants ở 05, T01–T27 ở 06, 11, 14 và provider ở 15.
- DevOps: thêm 04, Deployment trong 07, 12, 13, 15.
- OPS/FINANCE/MARKETING: thêm 14, flow tương ứng ở 06 và runbook ở 13.

## 3. Quy tắc cập nhật

1. Task ghi requirement ID, acceptance ID và tài liệu ảnh hưởng.
2. Thay đổi nghiệp vụ: PO quyết định; đổi boundary/contract/schema: TL và producer/consumer cùng review.
3. Cập nhật 08 trước hoặc cùng PR thay đổi; không dùng lại mã yêu cầu cũ cho nghĩa khác.
4. OpenAPI/JSON Schema được tạo trong PLT-02 từ 03. Khi có code, contract thực thi và tài liệu phải cùng PR; CI kiểm tra drift.
5. Trạng thái task: Planned, In progress, Review, Done, Blocked. Hiện mọi task là Planned, owner cụ thể chưa gán.
6. Chỉ đánh dấu đã test/deploy/duyệt khi có bằng chứng, người xác nhận và ngày.

## 4. Thuật ngữ chung

| Thuật ngữ | Nghĩa trong dự án |
|---|---|
| Phase 0 / 1 / 2 / 3 | Nền tảng / MVP / hoàn thiện Release 1 / tối ưu sau đó |
| P0 / P1 / P2 | Bắt buộc MVP / bắt buộc hoàn thiện Release 1 / sau Release 1 |
| Reserve / commit / release / return | Giữ available / trừ on_hand và reserved / nhả reserved / nhập lại hàng đã commit |
| UNKNOWN | Chưa xác định kết quả mutation; query cùng reference |
| MANUAL | Cần người xử lý; nghĩa vụ tài chính/kho vẫn còn |
| Outbox | Business write và event intent cùng local transaction, relay at-least-once |
| Idempotency | Retry cùng tác vụ không gây thêm hiệu ứng |
| order_id / order_no | UUID nội bộ / mã hiển thị, không tráo nhau |
| Baseline | Bộ giả định nhất quán dùng chia việc; không đồng nghĩa phê duyệt production |
