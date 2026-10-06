# Fashion E-commerce Platform

Nền tảng bán lẻ thời trang một shop tại Việt Nam: React storefront/admin, 9 service Spring Boot, PostgreSQL, Kafka, Redis và Kubernetes.

Repo có tài liệu, CI và nền tảng S1-local; Phase 1A đã có `user-service` (auth, profile/địa chỉ,
RBAC/admin, mật khẩu và rate limit Redis), các thư viện durability/security, catalog mẫu,
Gateway và storefront shell. Có cấu hình AWS/GitOps chuẩn bị offline, chưa provision/deploy.
USR-01/02 còn chờ nghiệm thu; catalog/cart/checkout và UI nghiệp vụ chưa hoàn thành.
Các chỉ số tải là mục tiêu nghiệm thu, không phải kết quả đo.

Sprint 1 theo ADR-18 chỉ xây local-first MVP foundation: frontend shell → Gateway → service mẫu → PostgreSQL chạy được từ fresh clone. Không provision AWS hoặc tuyên bố toàn bộ business MVP/G0/G1/G2 hoàn thành trong Sprint 1.

## Bắt đầu ở đâu?

1. Đọc [cẩm nang tài liệu](docs/README.md) và [Project Brief](docs/design/01_brief.md).
2. Đọc [PRD](docs/design/02_prd.md), sau đó [quyết định và vấn đề còn mở](docs/design/08_decisions.md).
3. Chia việc từ [kế hoạch nhóm](docs/delivery/09_delivery_plan.md) và [backlog có tiêu chí nghiệm thu](docs/delivery/10_backlog.md).
4. Khi nhận task, tra [API/event](docs/design/03_interfaces.md), [schema](docs/design/05_database_design.md), [service flows](docs/design/06_service_flows.md), [kiểm thử](docs/quality/11_test_strategy.md).
5. Triển khai cloud sau Sprint 1: đọc [AWS staging và CI/CD](docs/engineering/16_aws_deployment.md). AWS đã chọn; EC2 + K3s là đề xuất cho credit $200, chưa provision.
6. Tra cứu công nghệ và phiên bản: [Tech Stack](docs/engineering/17_tech_stack.md). Hồ sơ R1 đã chọn exact versions sau research; phần S1 có code, lockfile và evidence local/CI, O02 vẫn mở cho nghiệm thu và phần compatibility còn lại.

## Chạy local (S1-local)

Cần JDK 21, Node 24.21.0/npm 11.19.0 (`.tool-versions`), Docker với Compose và Python 3.10+. Từ repo root:

```bash
bash scripts/local-up.sh        # build JAR/dist, chạy postgres → catalog + user-service → gateway → storefront
bash scripts/smoke-local.sh     # smoke xuyên chuỗi
docker compose --env-file infra/local/.env -f infra/local/compose.yaml down   # giữ volume
```

Storefront: `http://localhost:4173`; Gateway: `http://localhost:8080`. Catalog không publish port. Lệnh test đầy đủ, proof Nacos và giới hạn ở [12 Engineering guide](docs/engineering/12_engineering_guide.md).

Trên Windows, dùng Git Bash cho các script `.sh`; backend có `mvnw.cmd` cho PowerShell.
Máy cần được phép tải toolchain/dependency. [Evidence ngày 2026-10-02](docs/evidence/s1-local-2026-10-02.md)
ghi rõ các kiểm tra đã chạy và phần fresh-clone runtime bị mạng công ty chặn.
[Fresh-clone review ngày 2026-10-04](docs/evidence/s1-local-2026-10-04.md) chạy đủ chuỗi trên Windows
sau khi sửa lỗi timezone; S1-local đã được Codex (GPT-6) nghiệm thu theo chỉ định
chủ dự án ngày 2026-10-04, với CI PASS trên patch `b62f963`. Đây không phải G0/G1/G2.
Contract core đã review; [platform-durability](services/platform-durability/README.md) được
user-service dùng cho outbox/idempotency, nhưng chưa có relay Kafka/notification-service.
Chi tiết phần auth đang review ở [User service](services/user-service/README.md).
Tiến độ mới nhất và bước tiếp ở [handoff USR-01b-ii](docs/superpowers/plans/2026-10-06-usr-01b-ii-handoff.md).

Chuyển sang máy mới: dùng [handoff S1-local](docs/superpowers/plans/2026-10-02-s1-local-machine-handoff.md)
để tiếp tục đúng checklist và ghi evidence trên commit được pull từ `origin/dev`.

## CI hiện có

[Documentation CI](.github/workflows/docs-ci.yml) kiểm tra code fences, JSON và đường dẫn link nội bộ khi push/PR. Chạy local:

```bash
python3 -B -m unittest discover -s scripts -p 'test_*.py' -v
python3 -B scripts/check_docs.py
```

Cần Python 3.10+. Chưa kiểm tra anchor/URL ngoài/render Mermaid. [Application CI](.github/workflows/application-ci.yml) chạy toolchain check, Maven/Testcontainers, frontend, contract, local smoke và Playwright, không có quyền AWS; chưa có CD. Đã xác minh [Application CI PASS](https://github.com/trunghieunef/fashion-ecommerce-platform/actions/runs/36662487345) và [Documentation CI PASS](https://github.com/trunghieunef/fashion-ecommerce-platform/actions/runs/36662487374) trên commit `d16151d` ngày 2026-09-30. Các run này chứng minh commit đó, không bao gồm thay đổi chưa commit hoặc thay fresh-clone review/TL acceptance.

## Trạng thái bộ tài liệu

Baseline **B1 — 2026-09-19**, đã hợp nhất nội dung để lập kế hoạch và triển khai theo từng task. Các giả định D01–D12 và vấn đề O01–O10 được theo dõi riêng; việc đồng bộ tài liệu không thay thế phê duyệt kinh doanh, ngân sách hay nghiệm thu. Lịch sử bản cũ được giữ trong Git.

MVP là Phase 1; Release 1 gồm Phase 1 và Phase 2. Promotion/OTP/social/carrier bên ngoài thuộc Phase 2. MVP vẫn có SELF shipping, quyền admin, refund, khôi phục saga và đối soát tối thiểu.
