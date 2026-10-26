# Fashion E-commerce Platform

Nền tảng bán lẻ thời trang một shop tại Việt Nam: storefront React (VI/EN, VND), admin API, 9 service Spring Boot, PostgreSQL,
Kafka, Redis; Kubernetes/AWS là đích triển khai sau này.

**Trạng thái (2026-10-26):** luồng mua hàng **COD** đã chạy được từ đầu tới cuối trên local — duyệt sản phẩm → giỏ hàng → đăng
nhập/gộp giỏ → checkout COD → OPS xác nhận → đóng gói → giao SELF → FINANCE đối soát, kèm hủy/hoàn hàng và email giao dịch.
Thanh toán online đang làm (VNPay PAY-02 code local xong, chờ sandbox thật); MoMo, refund, màn hình admin chưa có. Mọi "Done"
dưới đây là **Done (local, trước G0)**: chưa có staging/AWS, chưa G1/G2. Các chỉ số tải là mục tiêu nghiệm thu, chưa đo.

## Bắt đầu ở đâu?

1. [Cẩm nang tài liệu](docs/README.md), [Project Brief](docs/design/01_brief.md), [PRD](docs/design/02_prd.md),
   [quyết định và vấn đề mở](docs/design/08_decisions.md).
2. Chia việc: [kế hoạch nhóm](docs/delivery/09_delivery_plan.md), [backlog + tiêu chí nghiệm thu](docs/delivery/10_backlog.md)
   (nguồn sự thật về trạng thái task).
3. Khi nhận task: [API/event](docs/design/03_interfaces.md), [schema](docs/design/05_database_design.md),
   [service flows](docs/design/06_service_flows.md), [frontend behavior](docs/design/14_frontend_behavior.md),
   [kiểm thử](docs/quality/11_test_strategy.md), [engineering guide](docs/engineering/12_engineering_guide.md).
4. Provider bên ngoài: [15 Integrations](docs/engineering/15_integrations.md). Cloud: [16 AWS](docs/engineering/16_aws_deployment.md)
   (EC2 + K3s là đề xuất, chưa provision). Phiên bản: [17 Tech Stack](docs/engineering/17_tech_stack.md) (O02 còn mở).
5. Spec, plan và handoff theo task: [docs/superpowers/plans](docs/superpowers/plans/README.md); bằng chứng và nghiệm thu:
   [docs/evidence](docs/evidence/).

## Thành phần

| Thành phần | Task (trạng thái) | Nội dung |
|---|---|---|
| [user-service](services/user-service/README.md) | USR-01/02 Done | Đăng ký/đăng nhập/refresh, profile, sổ địa chỉ, RBAC/admin + audit, rate limit Redis |
| [catalog-service](services/catalog-service/README.md) | CAT-01/02/03 Done | Catalog admin, browse/search/quote public, media S3 private |
| [inventory-service](services/inventory-service/README.md) | INV-01/02/03 Done | Stock ledger, adjust/import, reserve/commit/release/expiry, return |
| [cart-service](services/cart-service/README.md) | CART-01/02/03 Done | Giỏ guest/member, gộp giỏ, snapshot/cleanup cho order, checkout token guest |
| [shipping-service](services/shipping-service/README.md) | SHP-01/02 Done | Phí SELF (bảng mẫu, O04), vận đơn SELF, sự kiện giao, COD thu/nộp |
| [payment-service](services/payment-service/README.md) | PAY-01 Done; PAY-02 In progress | COD, close/tombstone, settlement; VNPay link/IPN/return/QueryDR (chờ sandbox thật) |
| [order-service](services/order-service/README.md) | ORD-01–04 Done | Quote ký, tạo đơn idempotent, saga COD, OPS xác nhận/hủy, hoàn hàng |
| [notification-service](services/notification-service/README.md) | NOT-01 Done | 6 email giao dịch VI/EN qua SMTP (local: Mailpit), chưa provider thật (O07) |
| [gateway](services/gateway/README.md) | — | Route public/admin, CSRF, chặn `/internal` |
| [storefront](web/storefront/README.md) | WEB-01/01b/02/03 Done | Trang chủ/danh mục/tìm kiếm/chi tiết, tài khoản, giỏ hàng, checkout COD, trang đơn |
| Thư viện dùng chung | PLT-03 Done | [platform-durability](services/platform-durability/README.md) (outbox/inbox/idempotency/lease), [platform-messaging](services/platform-messaging/README.md) (Kafka relay/consumer), [platform-security](services/platform-security/README.md) (service token ADR-19, JWT) |

Chưa làm: MoMo (PAY-03), refund/đối soát (PAY-04), hoàn tất đơn online (ORD-05), UI thanh toán online (WEB-04), admin UI
(ADM-01/02), QA/load/restore (QA-*, OPS-*). Nền tảng PLT-01/02/04/05, SEC-01 còn chờ O01/O02/O06. Chi tiết: [backlog](docs/delivery/10_backlog.md).

## Chạy local

Cần JDK 21, Node 24.21.0/npm 11.19.0 (`.tool-versions`), Docker với Compose và Python 3.10+. Từ repo root:

```bash
bash scripts/local-up.sh        # sinh infra/local/.env (khóa local), build JAR/dist, chạy toàn bộ stack
bash scripts/smoke-local.sh     # smoke xuyên chuỗi qua Gateway, kể cả browser smoke
docker compose --env-file infra/local/.env -f infra/local/compose.yaml down   # dừng, giữ volume
```

- Storefront `http://localhost:4173`, Gateway `http://localhost:8080`, Mailpit `http://127.0.0.1:8025`, Grafana
  `http://127.0.0.1:3000`. Các service khác không publish port (payment chỉ `127.0.0.1:8086` cho script sandbox VNPay).
- Windows: chạy script `.sh` bằng Git Bash; backend có `mvnw.cmd` cho PowerShell. Máy ít RAM: chạy test từng module,
  ví dụ `./mvnw -pl services/payment-service test` (cần Docker cho Testcontainers).
- VNPay sandbox (tùy chọn): điền thông tin merchant sandbox vào `infra/local/.env` theo
  [payment-service README](services/payment-service/README.md#vnpay-sandbox); không commit secret.
- Lệnh test đầy đủ, lưu ý Docker/Testcontainers và giới hạn: [12 Engineering guide](docs/engineering/12_engineering_guide.md) §1.

## CI

- [Documentation CI](.github/workflows/docs-ci.yml) (mọi push/PR): code fences, JSON, link nội bộ. Chạy local:

  ```bash
  python3 -B -m unittest discover -s scripts -p 'test_*.py' -v
  python3 -B scripts/check_docs.py
  ```

  Chưa kiểm anchor, URL ngoài, render Mermaid.
- [Application CI](.github/workflows/application-ci.yml) (PR và push `main`): toolchain/lockfile, Maven + Testcontainers,
  frontend typecheck/test/build, contract, local stack + smoke, Playwright browser smoke. Không có quyền AWS, chưa có CD;
  [publish-images](.github/workflows/publish-images.yml) đang khóa tới PLT-04.

## Cách làm việc

Quy tắc cho người và agent ở [AGENTS.md](AGENTS.md): làm theo task trong backlog (spec → plan → TDD → review → evidence),
commit trên `dev`, PR `dev → main`; mọi thay đổi đồng bộ tài liệu cùng PR. Không lưu secret, token hay dữ liệu khách thật vào
Git; tiền/kho theo guardrails ở AGENTS.md.

## Trạng thái bộ tài liệu

Baseline **B1 — 2026-09-19**. Giả định D01–D12 và vấn đề O01–O10 được theo dõi ở [08](docs/design/08_decisions.md); đồng bộ tài
liệu không thay phê duyệt kinh doanh, ngân sách hay nghiệm thu. S1-local nghiệm thu 2026-10-04
([evidence](docs/evidence/s1-local-2026-10-04.md)); G0 hoãn tới sau G1.

MVP là Phase 1; Release 1 gồm Phase 1 và Phase 2. Promotion/OTP/social/carrier bên ngoài thuộc Phase 2. MVP vẫn có SELF
shipping, quyền admin, refund, khôi phục saga và đối soát tối thiểu.
