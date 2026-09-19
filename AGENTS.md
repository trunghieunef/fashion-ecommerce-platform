# Hướng dẫn agent — Fashion E-commerce Platform

## Phạm vi và cách làm việc

- Áp dụng cho toàn repository; đọc thêm chỉ dẫn trong thư mục con nếu có trước khi sửa file tại đó.
- Trao đổi bằng tiếng Việt, giữ nguyên tên kỹ thuật và identifier trong code. Báo ngắn gọn kết quả, kiểm thử và phần chưa hoàn tất.
- Yêu cầu giải thích/review/chẩn đoán: kiểm tra và báo cáo, không tự sửa hoặc deploy. Yêu cầu triển khai tính năng: thực hiện phần được giao, kiểm thử và cập nhật tài liệu liên quan.
- Trước khi sửa, kiểm tra `git status --short` và diff liên quan. Giữ nguyên thay đổi đang làm dở của người dùng; không reset, ghi đè hoặc dọn file ngoài phạm vi task.
- Ưu tiên `rg`/`rg --files` để tìm code và tài liệu. Dùng `apply_patch` khi công cụ hỗ trợ; sửa nhỏ, đúng nguyên nhân, không refactor lan rộng.
- Không tự commit, push, tạo PR, thay branch protection hoặc gọi hệ thống bên ngoài có tác dụng ghi khi chưa được yêu cầu.
- Nếu thiếu quyết định làm thay đổi nghiệp vụ, contract, chi phí hoặc dữ liệu: nêu rõ blocker và hỏi người dùng; tiếp tục phần độc lập an toàn. Không hỏi lại những điều repo đã xác định.

## Trạng thái và phạm vi sản phẩm

Tại thời điểm tạo file (2026-09-19), repo có tài liệu và CI kiểm tra tài liệu; chưa có ứng dụng, migration, Dockerfile hoặc hạ tầng chạy được. Luôn kiểm tra filesystem và cấu hình hiện tại trước khi dựa vào mô tả này. Khi thêm tooling thật, cập nhật hướng dẫn/lệnh tương ứng, không giữ tuyên bố trạng thái đã lỗi thời.

- Sản phẩm: một shop thời trang tại Việt Nam, storefront/admin, VI/EN, VND.
- Phase 0 là nền tảng; Phase 1 là MVP; Release 1 gồm Phase 1 + Phase 2.
- MVP gồm COD/SELF shipping, VNPay/MoMo, phân quyền admin, refund, saga recovery và đối soát tối thiểu. Demo COD G1 chưa phải toàn bộ MVP G2.
- Promotion, OTP/social login, carrier bên ngoài và các chức năng Phase 2 không tự kéo vào MVP. Không tự đổi scope/stack để giảm khối lượng.
- Java 21, Spring Boot 4.0, React TypeScript + Vite SPA, PostgreSQL, Kafka, Redis, Gateway, Nacos, Kubernetes là lựa chọn hiện hành theo R1/ADR-16/17. Exact versions tại 17 là bộ được chọn sau research, chưa kiểm thử runtime; PLT-01/O02 phải xác minh, không tự dùng `latest`. PO/FE xác nhận đánh đổi SEO của SPA trước WEB-01.
- ADR-18 giới hạn Sprint 1 ở checkpoint S1-local: fresh clone chạy frontend shell → Gateway → service mẫu → PostgreSQL trên local. Không provision AWS/K3s/ECR/ArgoCD, không gọi provider thật và không báo toàn bộ MVP/G0/G1/G2 hoàn tất trong Sprint 1.

## Đọc tài liệu theo task

Bắt đầu với [README](README.md) và [cẩm nang tài liệu](docs/README.md). Đọc đầy đủ phần liên quan đến task, không cần nạp lại toàn bộ tài liệu cho mỗi sửa đổi nhỏ.

| Công việc | Nguồn cần đối chiếu |
|---|---|
| Phạm vi, giả định, quyết định | [01 Brief](docs/design/01_brief.md), [02 PRD](docs/design/02_prd.md), [08 Decisions](docs/design/08_decisions.md) |
| Phân công, dependency, acceptance | [09 Delivery](docs/delivery/09_delivery_plan.md), [10 Backlog](docs/delivery/10_backlog.md) |
| API/event, backend, database | [03 Interfaces](docs/design/03_interfaces.md), [04 Architecture](docs/design/04_architecture.md), [05 Database](docs/design/05_database_design.md), [06 Flows](docs/design/06_service_flows.md) |
| Frontend | 03 và [14 Frontend behavior](docs/design/14_frontend_behavior.md); đọc 06 nếu sửa checkout/payment/cancel |
| Test và quy trình phát triển | [11 Test strategy](docs/quality/11_test_strategy.md), [12 Engineering guide](docs/engineering/12_engineering_guide.md) |
| Tech stack và phiên bản | [17 Tech Stack](docs/engineering/17_tech_stack.md); quyết định/support/compatibility tại O02 trong 08 |
| Provider, bảo mật, triển khai | [13 Operations](docs/operations/13_operations_security.md), [15 Integrations](docs/engineering/15_integrations.md), [16 AWS](docs/engineering/16_aws_deployment.md) |

03 sở hữu contract, 05 sở hữu schema, 06 sở hữu state machine; [07 Diagrams](docs/design/07_diagrams.md) là góc nhìn minh họa, không tự ghi đè các nguồn đó. Khi phát hiện mâu thuẫn, báo rõ và đồng bộ tài liệu liên quan trong phạm vi thay đổi; không coi file mới hơn là đã được phê duyệt.

## Thực hiện task và đồng bộ

- **Mọi thay đổi đều phải đồng bộ tài liệu trong cùng task/PR.** Khi sửa code, cấu hình, dependency, contract, schema, migration, workflow CI/CD, hạ tầng, test hoặc hành vi vận hành, phải cập nhật tài liệu chủ quản và README/runbook liên quan. Không xem task hoàn thành nếu implementation, test và docs chưa nhất quán. Nếu thay đổi thực sự không ảnh hưởng nội dung tài liệu, phần bàn giao vẫn phải ghi rõ `Docs: N/A` cùng lý do; không được im lặng bỏ qua bước đánh giá tài liệu.
- Với task trong backlog, ghi rõ `TASK:<id>`, REQ liên quan, dependency và acceptance. TASK và REQ là hai namespace khác nhau dù mã có thể giống nhau.
- Chỉ triển khai module/service cần cho task; không tạo sẵn toàn bộ cây thư mục, abstraction, dependency hoặc framework cho tính năng chưa làm.
- Khi PLT-01 tạo code, theo cấu trúc dự kiến ở 12: `services/`, `web/`, `contracts/`, `infra/`, `tests/`. Không coi các đường dẫn dự kiến là đã tồn tại.
- Trước đổi contract, đọc producer/consumer và caller liên quan. Cập nhật OpenAPI/JSON Schema nếu đã có, tests và tài liệu trong cùng thay đổi; chưa có contract thực thi thì không tuyên bố compatibility đã pass.
- Khi đổi quyết định kiến trúc/nghiệp vụ, ghi tác động vào 08; không tự biến D01–D12 hoặc O01–O10 thành quyết định đã duyệt.
- Mỗi service khi được tạo cần README có lệnh chạy/test thật, config, migration, topics, health, observability và giới hạn. Ưu tiên library/platform có sẵn, tránh dependency và lớp trung gian không cần thiết.

## Quy tắc tiền, kho và bảo mật

Các điểm dưới đây là guardrails; chi tiết và trường hợp ngoại lệ phải đọc 03/05/06/13/15.

- PostgreSQL là nguồn sự thật cho stock/quota/payment/cart; Redis không quyết định tồn kho hoặc số tiền được hoàn.
- Database/credential riêng theo service, không truy vấn chéo DB hoặc share JPA entity. Local transaction chỉ trong DB owner; không gọi HTTP/provider khi giữ transaction/row lock.
- Order là bên điều phối checkout duy nhất. Không để REST command và event handler cùng tạo side effect cho một thao tác.
- Giá/tổng tiền lấy từ server quote; dùng integer/decimal VND với kiểm tra overflow, không float/double hoặc amount từ client làm authority.
- Retry dùng cùng operation key/business reference. Timeout mutation là `UNKNOWN`, không tự coi là thất bại hoặc tạo giao dịch/reference mới.
- Outbox/inbox và thay đổi nghiệp vụ phải durable, dedupe đúng transaction; consumer chỉ ACK sau commit. Không fire-and-forget công việc tiền/kho.
- Phân biệt reserve/commit/release/return; không báo hủy/hoàn tất trước khi nghĩa vụ đã được xử lý theo state machine. Không sửa counter/ledger bằng tay để che lỗi.
- Callback phải kiểm tra chữ ký/auth, merchant, reference, amount và currency theo provider; redirect trình duyệt không chứng minh đã thanh toán.
- Kiểm tra quyền/ownership tại service, không chỉ UI/Gateway. Không trust header danh tính hoặc role do client gửi; route internal không được public.
- Không lưu secret, credential, OTP, token hoặc dữ liệu khách hàng thật vào Git, fixture, log hay báo cáo. Dùng synthetic data và sandbox; ví dụ cấu hình chỉ chứa placeholder an toàn.
- Frontend giữ idempotency key qua retry, xử lý `202`/`409`/`UNKNOWN` trung thực; có loading/empty/error/permission states. Access token in-memory, refresh cookie và CSRF theo 12/13.
- Migration đã deploy là append-only; thay đổi schema theo hướng tương thích. Không drop dữ liệu hoặc rollback schema phá hủy để làm test pass.

## Kiểm tra trước khi bàn giao

Lệnh hiện có, chạy từ repo root với Python 3.10+, không cần dependency ngoài stdlib:

```bash
python3 -B -m unittest discover -s scripts -p 'test_*.py' -v
python3 -B scripts/check_docs.py
git diff --check
```

- Checker hiện chỉ quét root README và `docs/**/*.md`: code fences, JSON syntax và đường dẫn inline link nội bộ. Chưa quét `AGENTS.md`, anchor, reference-style link, URL ngoài hoặc render Mermaid; kiểm tra riêng các phần đó khi sửa.
- Với code ứng dụng được thêm sau này, dùng wrapper/package scripts thực có và bổ sung regression test phù hợp. Không bịa lệnh Maven/npm hoặc báo pass khi chưa chạy.
- Money/stock/concurrency cần integration test PostgreSQL thật theo 11; mock hoặc H2 không thay bằng chứng lock/constraint/race. Có negative, duplicate, timeout và recovery cases liên quan.
- Đổi hạ tầng cần validate cấu hình và kiểm tra plan/change set; không coi validate là đã deploy. Không execute tài nguyên tính phí chỉ để kiểm thử cấu hình nếu chưa được duyệt.
- Nêu chính xác lệnh đã chạy, kết quả, phần không chạy và lý do. Không đánh dấu task/G0/G2 Done chỉ từ docs CI, unit test hoặc mock demo; cần đủ acceptance và reviewer theo 09/10.

## AWS và tác động bên ngoài

- AWS đã được chọn; $200 credit do chủ dự án cung cấp chưa đồng nghĩa số dư được kiểm chứng, ngân sách tháng hoặc quyền chi tiền.
- EC2 + K3s single-node là đề xuất staging, chưa phải quyết định production/HA. Không tự mua EKS, RDS, NAT Gateway hoặc tăng máy để vượt giới hạn sizing.
- Trước provision/deploy: xác nhận account/region, plan/hạn credit, tổng chi phí, quyền thao tác, scope tài nguyên và approval theo 16. Không tự nâng Paid Plan hoặc tạo/join Organizations/Control Tower.
- Không yêu cầu access key/root password/MFA qua chat. Ưu tiên cơ chế session ngắn hạn và least privilege; OIDC cho CI→AWS khi được triển khai.
- Không mở DB/Kafka/Redis/Nacos/ArgoCD/Kubernetes API ra Internet hoặc cấp cluster-admin/AWS admin cho pipeline để vượt lỗi quyền.
- Không gọi thanh toán/refund/gửi thông báo thật khi task chỉ yêu cầu code/test. Production cần phê duyệt riêng; staging pass không tự cho phép promotion.
- Không terminate máy, xóa stack/bucket/volume, purge dữ liệu hoặc rotate secret ngoài phạm vi đã được duyệt. Đối chiếu chính xác target, backup/retention và khả năng khôi phục trước thao tác phá hủy.

## Bàn giao

Tóm tắt thay đổi và file chính; liệt kê tài liệu đã đồng bộ hoặc `Docs: N/A` kèm lý do; liệt kê kiểm thử thực chạy, rủi ro/blocker còn lại và bước tiếp theo. Phân biệt rõ: đề xuất, đã viết cấu hình, đã kiểm tra local, đã chạy CI remote, đã deploy và đã nghiệm thu. Không công bố đã hoàn thành thay cho bằng chứng.
