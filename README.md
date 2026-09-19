# Fashion E-commerce Platform

Nền tảng bán lẻ thời trang một shop tại Việt Nam: React storefront/admin, 9 service Spring Boot, PostgreSQL, Kafka, Redis và Kubernetes.

Repo hiện chứa **tài liệu thiết kế, kế hoạch triển khai và CI kiểm tra tài liệu**; chưa có ứng dụng, migration hoặc hạ tầng đã chạy. Các chỉ số tải là mục tiêu nghiệm thu, không phải kết quả đo.

Sprint 1 theo ADR-18 chỉ xây local-first MVP foundation: frontend shell → Gateway → service mẫu → PostgreSQL chạy được từ fresh clone. Không provision AWS hoặc tuyên bố toàn bộ business MVP/G0/G1/G2 hoàn thành trong Sprint 1.

## Bắt đầu ở đâu?

1. Đọc [cẩm nang tài liệu](docs/README.md) và [Project Brief](docs/design/01_brief.md).
2. Đọc [PRD](docs/design/02_prd.md), sau đó [quyết định và vấn đề còn mở](docs/design/08_decisions.md).
3. Chia việc từ [kế hoạch nhóm](docs/delivery/09_delivery_plan.md) và [backlog có tiêu chí nghiệm thu](docs/delivery/10_backlog.md).
4. Khi nhận task, tra [API/event](docs/design/03_interfaces.md), [schema](docs/design/05_database_design.md), [service flows](docs/design/06_service_flows.md), [kiểm thử](docs/quality/11_test_strategy.md).
5. Triển khai cloud sau Sprint 1: đọc [AWS staging và CI/CD](docs/engineering/16_aws_deployment.md). AWS đã chọn; EC2 + K3s là đề xuất cho credit $200, chưa provision.
6. Tra cứu công nghệ và phiên bản: [Tech Stack](docs/engineering/17_tech_stack.md). Hồ sơ R1 đã chọn exact versions sau research: Java 21/Boot 4.0, React/Vite/Node 24, PostgreSQL 17; còn runtime compatibility/lockfile qua O02, chưa triển khai.

## CI hiện có

[Documentation CI](.github/workflows/docs-ci.yml) kiểm tra code fences, JSON và đường dẫn link nội bộ khi push/PR. Chạy local:

```bash
python3 -B -m unittest discover -s scripts -p 'test_*.py' -v
python3 -B scripts/check_docs.py
```

Cần Python 3.10+. Chưa kiểm tra anchor/URL ngoài/render Mermaid; chưa có CI build ứng dụng hoặc CD AWS. Workflow cần được push và có run GitHub để xác nhận hoạt động trên remote; cấu hình local không tự bật branch protection.

## Trạng thái bộ tài liệu

Baseline **B1 — 2026-09-19**, đã hợp nhất nội dung để lập kế hoạch và triển khai theo từng task. Các giả định D01–D12 và vấn đề O01–O10 được theo dõi riêng; việc đồng bộ tài liệu không thay thế phê duyệt kinh doanh, ngân sách hay nghiệm thu. Lịch sử bản cũ được giữ trong Git.

MVP là Phase 1; Release 1 gồm Phase 1 và Phase 2. Promotion/OTP/social/carrier bên ngoài thuộc Phase 2. MVP vẫn có SELF shipping, quyền admin, refund, khôi phục saga và đối soát tối thiểu.
