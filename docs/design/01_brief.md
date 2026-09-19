# 01 — Project Brief

B1 · 2026-09-19 · Baseline để lập kế hoạch; PO/TL chưa ký nghiệm thu hay ngân sách.

## 1. Bài toán và mục tiêu

Xây nền tảng bán lẻ thời trang **một shop** tại Việt Nam, giao diện VI/EN, VND. Khách duyệt sản phẩm, chọn size/màu, đặt hàng guest/member, thanh toán VNPay/MoMo/COD và theo dõi giao hàng. Đội OPS quản lý sản phẩm/kho/đơn; FINANCE quản lý tiền, refund và đối soát; MARKETING quản lý ưu đãi.

Mỗi size × màu là SKU có tồn riêng. Trải nghiệm ảnh, collection/lookbook và size guide phục vụ quyết định mua. Checkout phải chống oversell, không nhân đôi giao dịch khi retry và phục hồi được sau lỗi mạng/service.

## 2. Phạm vi

| Giai đoạn | Kết quả |
|---|---|
| Phase 0 | Repo, contracts, môi trường local/staging, CI, auth boundary, observability |
| Phase 1 — MVP | Email/password, guest/member, catalog, cart, order saga, inventory, VNPay/MoMo/COD, SELF shipping, email giao dịch, admin có RBAC/audit, refund và đối soát tối thiểu |
| Phase 2 — hoàn thiện Release 1 | Voucher/flash sale, GHN/GHTK/Viettel Post, OTP/social, reviews/wishlist/restock, marketing và báo cáo |
| Phase 3 | Tối ưu theo đo tải, search nâng cao, PWA, analytics/A/B test |

Release 1 = Phase 1 + Phase 2. Ngoài Release 1: marketplace nhiều seller, nhiều kho/split shipment, native mobile, đa tiền tệ, recommendation AI, live chat, cổng tự phục vụ đổi trả.

Một kho, một payment và một shipment/order là giả định D01/D06/D08. Các giới hạn thương mại phải được PO xác nhận theo [08 Decisions](08_decisions.md).

## 3. Kiến trúc và nguồn lực

9 service: user, catalog, cart, order, inventory, payment, promotion, shipping, notification. Java 21/Spring Boot 4.0, React TypeScript + Vite SPA, PostgreSQL per service, Kafka, Redis, Spring Cloud Gateway, Nacos, Kubernetes. Bộ phiên bản được chọn sau research ở [17](../engineering/17_tech_stack.md), thay định hướng Boot 3 qua ADR-16; còn kiểm thử compatibility và xác nhận đánh đổi SEO của SPA. AWS đã được chủ dự án chọn với $200 credit theo thông tin cung cấp; region, budget và topology được chốt trong Phase 0. Phương án staging tại [16](../engineering/16_aws_deployment.md); chưa có hạ tầng production.

Team dự kiến 3–5 dev, một người có thể kiêm TL/QA/DevOps. Ước lượng ban đầu: Phase 0 khoảng 2–3 tuần; Phase 1 thêm 6–8 tuần; Phase 2 thêm 4–6 tuần. MVP hướng tới một quý; toàn Release 1 khoảng 4–5 tháng là dự báo có điều kiện, phải điều chỉnh theo capacity và sandbox thực tế trong [09](../delivery/09_delivery_plan.md).

## 4. Mục tiêu đo lường

- Production availability >= 99.9%/tháng; RPO <= 15 phút, RTO <= 1 giờ, phải diễn tập.
- Catalog API p95 < 300 ms khi cache warm.
- Checkout p95 < 2 giây để tiếp nhận bền vững; trả 201 hoặc 202. Thời gian sẵn sàng payment URL đo riêng.
- >= 99.5% checkout đủ điều kiện không thất bại do hệ thống ở profile đã thống nhất.
- OPS thao tác xác nhận/đóng gói một đơn < 1 phút, không tính chờ carrier.
- Kiểm thử tăng dần 100 concurrent (MVP), 1.000 (Phase 2), 10.000 (Phase 3). Không đồng nhất concurrent users với request/giây.

Định nghĩa mẫu số, cửa sổ đo, dependency giả/thật và ngưỡng chi tiết ở [11 Test strategy](../quality/11_test_strategy.md). Đây là mục tiêu, chưa có bằng chứng đã đạt.

## 5. Rủi ro và điều kiện thành công

| Rủi ro | Biện pháp / owner |
|---|---|
| 9 service vượt sức team nhỏ | TL chia vertical slice, contract sớm, giới hạn WIP, chưa triển khai Phase 2 |
| Saga và tiền/kho lệch | BE/QA kiểm thử transaction thật, race, crash, replay; durable recovery |
| Bên thứ ba thiếu sandbox/quyền refund | PO/FINANCE lấy account sớm; BE mock độc lập; gate trước bật thật |
| Flash sale vượt tải | DEVOPS/QA đo hot SKU, rate limit, backpressure, kill switch |
| Scope/đổi trả/PII chưa rõ | PO chốt D01–D12 và O01–O10 trước gate liên quan |

Tuân thủ nghĩa vụ thương mại điện tử và bảo vệ dữ liệu áp dụng tại thời điểm launch là đầu việc O06, cần người có trách nhiệm rà soát. Tài liệu kỹ thuật không xác nhận tính đầy đủ pháp lý.

## 6. Phê duyệt và bàn giao

PO xác nhận scope/chính sách; TL xác nhận thiết kế; OPS/FINANCE xác nhận UAT; người chịu trách nhiệm vận hành xác nhận go-live. Ghi tên/ngày/bằng chứng tại milestone tương ứng trong 09. Không xem cập nhật tài liệu là đã phê duyệt những quyết định đó.
