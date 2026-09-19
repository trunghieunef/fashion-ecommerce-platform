# 08 — Quyết định, giả định và vấn đề còn mở

B1 · 2026-09-19 · Chủ trì: PO + TL · Người phê duyệt cụ thể: chưa gán.

## 1. Cách sử dụng

Baseline hợp nhất dùng để estimate, xây contract/mock và triển khai phần độc lập. Trước khi khóa acceptance hoặc bật tính năng thật, PO/TL ghi quyết định, tên, ngày và bằng chứng vào dòng tương ứng. Không cần chờ mọi vấn đề mở để bắt đầu Phase 0.

## 2. Giả định D01–D12

| ID | Baseline đang dùng | Trạng thái / người xác nhận | Ảnh hưởng nếu đổi | Gate |
|---|---|---|---|---|
| D01 | Một shop, một kho logic | Giả định — PO | Allocation, kho, điều chuyển | Trước INV-01 |
| D02 | PostgreSQL quyết định stock/quota; Redis cache | Thống nhất kỹ thuật — TL review migration | Đổi nguồn sự thật cần ADR mới | Trước INV-02 |
| D03 | Giá server quote, snapshot lúc tạo đơn | Kỹ thuật thống nhất; PO xác nhận UX | Quote, thời hạn và giữ giá | Trước ORD-01 |
| D04 | Order điều phối checkout duy nhất | Thống nhất kỹ thuật — TL | Không thêm handler commit từ payment event | Trước ORD-02 |
| D05 | Online 15 phút; COD 24 giờ chờ OPS | Giả định — PO/OPS | Config deadline, UX, abuse và race | Trước ORD-03 |
| D06 | Một payment/order; một giao dịch chính bên cổng | Giả định — PO/FINANCE | Đổi cổng cần payment attempts | Trước PAY-01 |
| D07 | Tiền đến sau đóng đơn/hết reserve: hoàn toàn bộ | Giả định — PO/FINANCE | Saga và UI | Trước PAY-04 |
| D08 | Một shipment/order, không giao một phần | Giả định — PO/OPS | Split shipment ảnh hưởng kho/tiền/FE | Trước SHP-01 |
| D09 | Khách hủy WAITING_PAYMENT/CONFIRMED; OPS trước bàn giao | Giả định — PO/OPS | Sau bàn giao phải return và kiểm đếm | Trước ORD-04 |
| D10 | Refund toàn phần/một phần; đổi trả sau giao qua OPS | Giả định — PO/FINANCE | Điều kiện hàng, phí, hạn, bằng chứng | Trước G2 |
| D11 | Voucher giới hạn/người cần tài khoản xác minh | Giả định — PO | Cookie guest không đại diện một người | Trước PRO-01 |
| D12 | Một voucher/order; không áp voucher nếu có campaign item | Giả định — PO/MARKETING | Pricing/quota/rounding/FE cùng đổi | Trước PRO-01 |

Gate task là work package trong [backlog](../delivery/10_backlog.md). Mã task có thể trùng requirement ID; ghi rõ “task” hoặc “REQ” khi dẫn.

## 3. ADR kỹ thuật của baseline

| ID | Quyết định | Lý do / giới hạn |
|---|---|---|
| ADR-01 | Giữ 9 boundary service | Theo brief; promotion deploy Phase 2; không sinh service phụ |
| ADR-02 | Java 21, React TypeScript; định hướng Boot 3 ban đầu được thay bởi ADR-16 | Giữ lịch sử lựa chọn; bộ version hiện hành tại 17, runtime compatibility vẫn phải qua PLT-01 |
| ADR-03 | PostgreSQL per service, Flyway | Không cần 9 server; FK nội bộ; credentials riêng |
| ADR-04 | Durable order saga và recovery worker | Phục hồi crash/timeout bằng lease; không chỉ đợi event |
| ADR-05 | Outbox at-least-once và inbox dedupe | Producer idempotence không bảo đảm exactly-once xuyên DB |
| ADR-06 | REST command từ order; Kafka event trạng thái | Không cho REST/event cùng tạo side effect |
| ADR-07 | Cart guest/member lưu DB | Redis mất không mất giỏ; cookie chứa credential ngẫu nhiên |
| ADR-08 | Cart cleanup qua REST duy nhất | Durable cleanup work; giữ item đã sửa; không cleanup thêm từ event |
| ADR-09 | Notification chỉ gửi từ notification.events | Dedupe nghiệp vụ; không gửi lần hai từ domain topic |
| ADR-10 | SELF shipping từ MVP | Có giao hàng, thu/đối soát COD xuyên suốt; carrier ngoài Phase 2 |
| ADR-11 | 202 khi saga chưa chuẩn bị xong | Đo tiếp nhận và thời gian URL riêng |
| ADR-12 | Gateway/Nacos/Kubernetes/GitOps | Giữ stack; internal route private; Nacos metadata riêng |
| ADR-13 | Constraints và row locks cho stock/refund/quota | Load test hot SKU trước khi tối ưu |
| ADR-14 | State machine ở 06, schema ở 05 | PRD/diagram tham chiếu để tránh nhiều bản lệch |
| ADR-15 | AWS theo lựa chọn chủ dự án; credit $200 theo thông tin cung cấp | Đã chọn provider, chưa kiểm chứng Billing/duyệt chi phí; đề xuất EC2 + K3s staging ở 16, không phải quyết định production |
| ADR-16 | R1 chọn Java 21 LTS + Boot 4.0.8 + Cloud 2025.1.3 + Alibaba 2025.1.0.0; Nacos server 3.2.4/client 3.1.1 | Research theo yêu cầu chủ dự án ngày 2026-09-19; thay phần Boot 3 của ADR-02 để tránh train Cloud hết hỗ trợ. Không chọn Boot 4.1 ngoài mapping Alibaba; exact patches chưa test. Nguồn và gate tại 17; TL nghiệm thu PLT-01, chưa duyệt production |
| ADR-17 | React + Vite SPA cho storefront/admin, Node 24 LTS + npm; static hosting | Ưu tiên ít runtime và dễ deploy. Giả định MVP chưa bắt buộc SEO/HTML sản phẩm từ server; PO/FE xác nhận trước WEB-01. Nếu không đạt, mở lại lựa chọn SSR/prerender; không tự thêm Next.js/BFF. Version/peer constraints ở 17 |
| ADR-18 | Sprint 1 chỉ triển khai local; AWS/cloud bắt đầu sau checkpoint S1-local | Chủ dự án chốt 2026-09-19. Sprint 1 hoàn thành nền tảng local chạy từ fresh clone, không đồng nghĩa toàn bộ G2 MVP; không provision AWS/K3s/ECR/ArgoCD hoặc gọi provider thật. Phạm vi và exit ở 09/10 |

## 4. Vấn đề mở

| ID | Cần quyết định / đầu ra | Owner | Hạn gate | Có thể làm trước |
|---|---|---|---|---|
| O01 | AWS đã chọn nhưng hoãn khỏi Sprint 1; còn plan/số dư/hạn credit $200, region, ngân sách gộp và tiền tự trả, staging sizing, managed DB/Redis và prod cluster | PO + DEVOPS | Sau S1-local, trước PLT-04 / G2 | Sprint 1 local không bị chặn; đề xuất EC2 + K3s, cost worksheet và subtasks tại 16; chưa tạo tài nguyên |
| O02 | R1 đã chọn bộ version ở 17; còn nghiệm thu runtime compatibility, lockfile/digest, license/CVE và yêu cầu SEO trước WEB-01 | TL + DEVOPS + FE | PLT-01 core; PLT-02/04/05 theo tooling | Hiện thực spike từ R1/ADR-16/17; chưa có integration test, app CI hoặc deployment, không tắt verifier/ép peer dependencies để vượt lỗi |
| O03 | Merchant sandbox/production, callback domains, quyền query/refund VNPay/MoMo | PO + FINANCE | PAY-02/03 / G2 | Mock và negative tests |
| O04 | Phí ship/COD, vùng SELF, nguồn địa chỉ, carrier account/SLA | PO + OPS | SHP-01/03 | Fixture giả và adapter |
| O05 | Đổi trả, phí, hàng hỏng, bằng chứng, đơn tổng 0; giá đã gồm thuế hay chưa và yêu cầu hóa đơn | PO + FINANCE | ORD-01/04 / G2 | Baseline không cộng thuế ngoài giá niêm yết; chặn checkout total <= 0; nếu cần thuế tách dòng phải sửa pricing/schema trước code |
| O06 | Retention PII, điều khoản/consent, nghĩa vụ pháp lý hiện hành | PO + phụ trách pháp lý | SEC-01 / G2 | Data inventory, masking; chưa bật purge tài chính |
| O07 | Email/SMS provider, domain gửi, consent, query/retry hỗ trợ | PO + DEVOPS | NOT-01 / G2; OTP trước G3 | Mail sink và contract |
| O08 | Headcount, capacity, người QA, tên owner/reviewer | PO + TL | Planning đầu | Estimate theo role; chưa cam kết ngày |
| O09 | Bộ tải/dữ liệu/hạ tầng/ngân sách chứng minh SLO | TL + QA + DEVOPS | QA-03 / G2 | Script và synthetic dataset |
| O10 | Nội dung/ảnh/size guide VI/EN, brand assets, điều khoản bán | PO + FE | WEB-01 / G2 | UI với fixture ghi rõ là mẫu |

## 5. Nhật ký hợp nhất B1

- 01: bỏ Approved mâu thuẫn phiếu ký trống; phân biệt MVP/Release 1 và mục tiêu/kết quả đo.
- 02: giữ mã yêu cầu; phase rõ; RBAC/audit/refund/reconcile tối thiểu thuộc MVP; OTP/promotion Phase 2.
- 03: quote/202, UUID, guest ownership, internal mutation, API phục hồi, consumer đúng; bỏ client amount.
- 04: DB authoritative, durable saga, SELF; bỏ mô tả history như event sourcing.
- 05–07: giữ schema/flow, đồng bộ contract references; bổ sung close/cleanup và giới hạn policy.
- 09–15: phân công, dependencies, traceability, test gates, onboarding, runbook, FE và provider.
- Bổ sung AWS/CI: ghi nhận AWS + credit theo chủ dự án; thêm [16](../engineering/16_aws_deployment.md), CI tài liệu và test local; đồng bộ 01/04/09/10/12/13/index. Chưa phê duyệt ngân sách, chưa có run GitHub/deploy hoặc nghiệm thu PLT-04.
- Bổ sung [17 Tech Stack](../engineering/17_tech_stack.md): tập hợp lựa chọn baseline, công cụ chưa chọn, trạng thái/version và bằng chứng cần có cho O02. Nêu rủi ro support Boot/Cloud theo nguồn chính thức; không thay stack, không chốt version chưa test hoặc đánh dấu task Done.
- R1 research 2026-09-19 theo yêu cầu chọn phiên bản: 17 có exact version ứng viên và nguồn upstream; ADR-16 thay Boot 3 bằng Boot 4.0, ADR-17 chọn Vite SPA có gate SEO. Đây là lựa chọn để implementation, không phải compatibility đã pass; không đóng O02, PLT-01 hay cấp quyền provision AWS.
- ADR-18: chủ dự án giới hạn Sprint 1 ở local-first MVP foundation. Thêm checkpoint S1-local; AWS/PLT-04 và toàn bộ G1/G2 không được suy là commitment Sprint 1.

Mẫu quyết định mới: ID; vấn đề; lựa chọn; phương án khác và lý do; ảnh hưởng PRD/API/schema/test/task; người quyết định; ngày; link bằng chứng. Chưa có chữ ký phê duyệt giả định thương mại.
