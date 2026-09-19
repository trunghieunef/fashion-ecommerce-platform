# 09 — Kế hoạch triển khai và tổ chức nhóm

B1 · 2026-09-19 · Owner: TL + PO · Sprint 1 đã chốt local-only; lịch sau đó là dự báo, chưa gán tên người.

## 1. Vai trò và trách nhiệm

| Vai trò | Chịu trách nhiệm chính | Review / phối hợp |
|---|---|---|
| PO | Scope, D01–D12, nội dung, provider accounts, UAT và quyết định release | OPS/FINANCE, TL |
| TL / BE-A | Contracts, order saga, integration, code review | BE-B review money/race; QA review acceptance |
| BE-B | Inventory/payment/shipping và migration | TL; FINANCE với đối soát |
| BE-C nếu có | User/catalog/cart/notification, sau đó promotion | BE-A/BE-B |
| FE | Storefront/admin, VI/EN, UI states và browser E2E | PO + backend owner |
| DEVOPS | Local/staging/CI/secrets/observability/restore | TL |
| QA (có thể kiêm nhiệm) | Test plan/fixtures, traceability, report release | Người khác kiểm tra task tác giả |
| OPS / FINANCE / MARKETING | UAT đúng nghiệp vụ, bằng chứng và training | PO |

Đội 3 người: một BE kiêm TL, một BE kiêm DevOps, một FE; chia lịch QA/UAT rõ, không bỏ trách nhiệm. Đội 5 người có thể dùng 3 BE, 1 FE, 1 DEVOPS và phân QA rotation. Đây là phương án capacity, không xem số vai trò là số người cần tuyển.

Mỗi task có đúng một assignee chịu trách nhiệm cuối, một reviewer; người khác có thể hỗ trợ. Trước sprint planning điền tên thật cho vai trò và người nhận alert, provider access. Task chưa có owner không được đưa vào In progress.

### Sprint 1 — local-first MVP foundation

Quyết định phạm vi: Sprint 1 chỉ xây và triển khai trên máy local; không provision AWS, không dựng K3s/ECR/ArgoCD và không dùng merchant/provider thật. “MVP local” trong Sprint 1 nghĩa nền tảng phát triển tối thiểu có thể chạy và demo local, **không** có nghĩa toàn bộ yêu cầu G2 được hoàn thành trong một sprint.

Cam kết Sprint 1:

- PLT-01 hoàn thành: Maven Wrapper/BOM, npm lockfile, cấu trúc tối thiểu, Compose dependencies, một service mẫu, Gateway và frontend shell chạy từ fresh clone.
- PLT-02 phần core: OpenAPI/event schema cần cho service mẫu, fixture/mock và schema checks; không tạo contract giả cho toàn bộ service chưa làm.
- SEC-01 phần nền: config/secret placeholder, trust boundary và kiểm tra route internal/header spoofing cho phần đã chạy.
- Một local smoke path qua frontend → Gateway → service mẫu → PostgreSQL; có migration, health, log correlation và test tối thiểu. Dữ liệu synthetic, provider dùng stub/mail sink khi cần.
- README/lệnh chạy, dừng và reset local đã được một người khác tác giả kiểm tra; CI tài liệu tiếp tục chạy. CI ứng dụng chỉ bổ sung cho code thực có.

Không thuộc Sprint 1: toàn bộ business MVP/G1/G2, VNPay/MoMo sandbox, load 100 concurrent, production topology, AWS budget/provision, GitOps và HA. PLT-03 chỉ nhận phần primitive cần trực tiếp cho smoke path nếu còn capacity; không scaffold toàn bộ 9 service.

Exit Sprint 1: clean checkout trên máy reviewer chạy được bằng lệnh đã ghi, migration và smoke pass, không có secret thật, dependency/version evidence của O02 được lưu. Không gọi kết quả này là G0/G1/G2 hoặc “MVP hoàn tất”.

## 2. Milestone và exit gate

| Gate | Nội dung demo / bằng chứng bắt buộc | Điều kiện vào |
|---|---|---|
| S1-local — nền tảng local | Fresh clone chạy service mẫu, Gateway, frontend shell và PostgreSQL qua local workflow; migration/smoke/version evidence pass | PLT-01; phần core PLT-02/SEC-01; không cần O01/AWS |
| G0 — nền tảng | Fresh-clone onboarding chạy được; một service mẫu deploy staging; traces/logs/metrics; contracts core review | PLT-01–05, SEC-01; O01/O02/O08 đủ cho staging |
| G1 — COD vertical slice | Guest/member → quote → COD → OPS → SELF → DELIVERED → FINANCE settlement; hủy/expire và race tests | User/catalog/cart/stock/order/COD/SELF/notification + UI cơ bản |
| G2 — MVP online | G1 + VNPay và MoMo sandbox E2E, duplicate/late webhook, refund, recovery, security, restore, profile 100 concurrent, OPS UAT | P0 task Done; O03–O10 cho launch; không lỗi Sev1/Sev2 mở |
| G3 — Release 1 | Promotion/quota, carrier, OTP/social, reviews/wishlist/restock/marketing/report, 1.000 concurrent không oversell | P1 Done; D11/D12 và provider gates đã xác nhận |
| G4 — scale | Có bottleneck evidence, ADR tối ưu, benchmark 10.000 concurrent và chi phí | Backlog Phase 3 được PO ưu tiên riêng |

G2/G3 là khả năng phát hành sau review; không tự động triển khai production khi test pass. PO/TL/OPS/FINANCE/DEVOPS ghi tên, ngày, report và quyết định go/no-go trong release issue. Môi trường sandbox pass không phải đã kiểm chứng production.

AWS staging dùng credit $200 theo thông tin chủ dự án nhưng được hoãn khỏi Sprint 1. Phương án tiết kiệm, budget gates và phần CI/CD đã có/chưa có ở [16](../engineering/16_aws_deployment.md). S1-local và CI tài liệu không đủ G0. Staging single-node không miễn yêu cầu load/restore/HA trước production; O01/O09 phải duyệt lại sizing/ngân sách khi chuyển từ service mẫu sang toàn MVP.

## 3. Thứ tự và đường phụ thuộc

1. PLT-01/02/SEC-01: repo, version, contracts và danh tính. Mock API cho FE ngay sau review contract.
2. Hai nhánh song song theo capacity: user/catalog/cart và inventory/payment COD/SELF.
3. Order quote/saga nối các contract đã có. G1 cần NOT-01 và admin có quyền, không chỉ API happy path.
4. VNPay rồi MoMo (hoặc đảo khi sandbox sẵn), refund/reconcile/crash recovery và G2.
5. Promotion/carrier/OTP-social và retention/marketing/report theo Phase 2.

Critical path thực tế: contract → catalog/stock/cart/SELF fee → quote → durable saga → COD/payment → fulfillment/cancel/refund → E2E/restore → UAT. FE chạy với mock trong khi BE chưa xong; tích hợp phải dùng contract thật trước gate.

## 4. Dự báo capacity và sprint

Sprint 1 chỉ cam kết checkpoint S1-local nêu trên. Phase 0 đầy đủ dự kiến 2–3 tuần; Phase 1 thêm 6–8 tuần; Phase 2 thêm 4–6 tuần. Không gắn tuần với ngày lịch khi O08 chưa chốt. Estimate trong 10 là person-day làm việc tập trung, bao gồm code/test/doc, chưa cộng chờ sandbox/review.

Capacity sprint = số ngày làm thực tế của từng người trừ nghỉ/support/meeting. Lập kế hoạch tối đa khoảng 70% capacity lúc đầu, phần còn lại integration/review/unknown; đo lại sau hai sprint. Không cộng task của hai BE rồi chia cho toàn team vì FE/DEVOPS có kỹ năng và critical path khác.

Tổng từ backlog B1 (cộng person-day của package, không nhân thêm số vai trò ghi trong một dòng):

| Phần | Số package | Khoảng person-day |
|---|---|---|
| Phase 0 | 6 | 11–21 |
| Phase 1 | 34 | 70–122 |
| Phase 2 | 11 | 29–45 |
| Tổng Release 1 kể cả nền tảng | 51 | 110–188 |

Các khoảng này chưa gồm thời gian chờ nhà cung cấp hoặc thay scope. Đội 3 người có 30 person-day thô cho sprint 2 tuần, planning 70% là khoảng 21; kỹ năng và dependency có thể kéo dài lịch hơn phép chia tổng. Vì vậy mốc một quý là mục tiêu cần reforecast, không phải deadline đã được chứng minh bằng backlog.

Cadence gợi ý: sprint 1–2 tuần; planning chốt DoR; daily cập nhật blocker; giữa sprint review contract/integration; cuối sprint demo và retrospective. WIP một implementation chính/người; review và unblocking được ưu tiên trước nhận task mới.

## 5. Definition of Ready

- Requirement, phase và acceptance rõ; task trỏ đúng phần 03/05/06/14.
- Producer/consumer cùng review input/output/error/permission và fixture.
- Assignee/reviewer, estimate, dependency và gate policy ghi đầy đủ.
- Sandbox/secret hoặc mock thay thế được chỉ rõ; phần bị blocked tách riêng.
- Nếu package > 3 person-day, tách subtasks migration/contract/domain/API/FE/test có deliverable và dependency; không đổi phạm vi acceptance cha.

## 6. Definition of Done

- Acceptance pass với bằng chứng phù hợp trong 11; test invariant phải dùng DB thật.
- Review bởi người khác; migration/contract/docs cùng PR; không secret thật.
- CI pass; metrics/logs/redaction/health theo mức rủi ro.
- Với vertical slice: FE/admin tích hợp và demo ở môi trường của gate; Sprint 1 dùng local, từ G0 mới yêu cầu staging. Không đánh dấu hoàn tất chỉ vì backend unit test.
- Runbook/rollback/config hoặc training cập nhật nếu thay đổi vận hành.
- Liên kết PR, commit/image digest, test report, migration version và lỗi còn mở; TL xác nhận task Done.

## 7. Quy trình thay đổi và blocker

Scope mới: ghi ảnh hưởng requirement/API/schema/test/task/estimate ở 08; PO quyết định priority, TL review kỹ thuật. Không mở rộng sprint bằng yêu cầu miệng không truy vết.

Blocker ghi nguyên nhân, owner xử lý, bước đã thử, ngày cập nhật và task không bị ảnh hưởng có thể tiếp tục. Sandbox chưa sẵn: hoàn tất mock/contract/race tests, nhưng không đóng provider integration. Quyết định thương mại chưa chốt: không đoán là đã approved.

Mẫu release record:

| Gate | Ngày | Build/commit | Test/UAT/restore evidence | Người xác nhận | Kết quả / vấn đề còn mở |
|---|---|---|---|---|---|
| G0–G4 | Chưa có | Chưa có | Chưa có | Chưa gán | Chưa nghiệm thu |
