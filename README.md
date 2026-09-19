# Fashion E-commerce Platform

Nền tảng bán lẻ thời trang một shop tại Việt Nam: React storefront/admin, 9 service Spring Boot, PostgreSQL, Kafka, Redis và Kubernetes.

Repo hiện chứa **tài liệu thiết kế và kế hoạch triển khai**; chưa có ứng dụng, migration hoặc hạ tầng đã chạy. Các chỉ số tải là mục tiêu nghiệm thu, không phải kết quả đo.

## Bắt đầu ở đâu?

1. Đọc [cẩm nang tài liệu](docs/README.md) và [Project Brief](docs/design/01_brief.md).
2. Đọc [PRD](docs/design/02_prd.md), sau đó [quyết định và vấn đề còn mở](docs/design/08_decisions.md).
3. Chia việc từ [kế hoạch nhóm](docs/delivery/09_delivery_plan.md) và [backlog có tiêu chí nghiệm thu](docs/delivery/10_backlog.md).
4. Khi nhận task, tra [API/event](docs/design/03_interfaces.md), [schema](docs/design/05_database_design.md), [service flows](docs/design/06_service_flows.md), [kiểm thử](docs/quality/11_test_strategy.md).

## Trạng thái bộ tài liệu

Baseline **B1 — 2026-09-19**, đã hợp nhất nội dung để lập kế hoạch và triển khai theo từng task. Các giả định D01–D12 và vấn đề O01–O10 được theo dõi riêng; việc đồng bộ tài liệu không thay thế phê duyệt kinh doanh, ngân sách hay nghiệm thu. Lịch sử bản cũ được giữ trong Git.

MVP là Phase 1; Release 1 gồm Phase 1 và Phase 2. Promotion/OTP/social/carrier bên ngoài thuộc Phase 2. MVP vẫn có SELF shipping, quyền admin, refund, khôi phục saga và đối soát tối thiểu.
