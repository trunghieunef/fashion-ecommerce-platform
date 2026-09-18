# 01 — Project Brief: Nền tảng E-commerce Thời Trang

| | |
|---|---|
| **Ngày** | 2026-09-11 |
| **Trạng thái** | Approved — cơ sở cho PRD |
| **Phiên bản** | v1.0 |
| **Tài liệu liên quan** | [PRD chi tiết](02_prd.md) · [Database Design — Draft](05_database_design.md) · [Service Flows — Draft](06_service_flows.md) · [Bộ sơ đồ — Draft](07_diagrams.md) |

---

## 1. Tóm tắt (Executive Summary)

Xây dựng nền tảng bán hàng trực tuyến cho shop thời trang, mô hình **bán lẻ 1 shop**, thị trường **Việt Nam** (VN + EN, tiền tệ VND). Hệ thống theo kiến trúc **microservices** (Java Spring Boot) triển khai trên **Kubernetes**, thiết kế cho khả năng **chịu tải cao** khi có chiến dịch flash sale, nhưng khởi đầu với quy mô nhỏ và mở rộng dần.

## 2. Vấn đề & Cơ hội

**Vấn đề**: Shop thời trang cần kênh bán online hoàn chỉnh, đáp ứng nhu cầu đặc thù (nhiều biến thể size/màu, trải nghiệm ảnh, bán theo mùa) và **không sập khi flash sale**.

**Cơ hội**: Nền tảng bắt đầu từ con số 0 → không có nợ kỹ thuật cũ, thiết kế đúng kiến trúc ngay từ đầu, dễ scale khi kinh doanh tăng trưởng.

## 3. Mục tiêu

| # | Mục tiêu | Đo lường |
|---|---|---|
| 1 | Ra mắt web bán hàng responsive đầy đủ hành trình mua hàng | Go-live MVP ≤ 1 quý |
| 2 | Kiến trúc microservices scale ngang từng service | Scale độc lập, không block nhau |
| 3 | Xử lý giao dịch phân tán nhất quán (saga + Kafka) | Không mất đơn khi peak |
| 4 | Vận hành được bằng admin dashboard | OPS tự xử lý đơn < 1 phút |

## 4. Phạm vi

### Trong phạm vi (Release 1)

- **9 microservices**: user, catalog, cart, order, inventory, payment, promotion, shipping, notification
- **Hạ tầng**: Spring Cloud Gateway, Nacos, Kafka, PostgreSQL (1 DB/service), Redis, Kubernetes
- **Web**: SPA React responsive (khách) + React Admin
- **Thanh toán**: VNPay, MoMo, COD
- **Vận chuyển**: GHN, GHTK, Viettel Post, tự giao
- **Đăng nhập**: Email+password, OTP SMS, Facebook/Google, Guest checkout
- **Đặc thù thời trang**: Collections/Lookbook, Size guide, Reviews kèm ảnh, Wishlist + Restock alert, Zoom ảnh

### Ngoài phạm vi (sau Release 1)

Recommendation AI · Mobile app native · Marketplace nhiều seller · Đa tiền tệ quốc tế · Live chat

## 5. Tiêu chí thành công (Success Metrics)

| Tiêu chí | Chỉ số |
|---|---|
| Tính khả dụng | ≥ 99.9% uptime/tháng |
| Latency API đọc (p95) | < 300 ms |
| Checkout khi peak | < 2 s tạo đơn |
| Tỷ lệ đơn thành công ở flash sale | ≥ 99.5% |
| Peak tải thiết kế | Hàng chục nghìn concurrent (sau scale) |

## 6. Đội ngũ & Nguồn lực

| Hạng mục | Chi tiết |
|---|---|
| **Đội ngũ** | 3-5 dev (backend, 1 frontend, 1 DevOps chung) |
| **Stack** | Java 17+, Spring Boot 3, Spring Cloud, Kafka, PostgreSQL 15, Redis, React |
| **Hạ tầng** | Cloud (AWS/GCP) + Kubernetes; dev / staging / production |
| **Kỹ năng** | Đã quen Java/Spring Boot; có kiến thức cơ bản Docker/K8s |

## 7. Mốc thời gian (Timeline)

| Phase | Thời gian | Nội dung chính |
|---|---|---|
| Phase 0 — Nền tảng | 2-3 tuần | Monorepo, CI/CD, K8s, Kafka, PostgreSQL, Nacos, Gateway, Observability |
| Phase 1 — MVP lõi | 6-8 tuần | Luồng mua hàng hoàn chỉnh: catalog → cart → order → inventory → payment (VNPay/MoMo/COD) |
| Phase 2 — Vận hành & Marketing | 4-6 tuần | Voucher/flash sale, carrier GHN/GHTK/VTPL, restock, reviews, OTP/social login |
| Phase 3 — Tối ưu & mở rộng | Liên tục | Elasticsearch, autoscaling, PWA, A/B test |

**Tổng đến launch chính thức**: ~ 4-5 tháng

## 8. Ràng buộc & Giả định

**Ràng buộc**:
- Phải chịu được peak flash sale mà không mất đơn (chống oversell).
- Tuân thủ quy định TMĐT & bảo vệ dữ liệu cá nhân Việt Nam (Nghị định 52/2013, 13/2023).
- Chi phí vận hành hợp lý với đội 3-5 dev.

**Giả định**:
- Quy mô khởi đầu vài nghìn đơn/ngày, thiết kế sẵn cho scale lớn.
- Thanh toán/vận chuyển dùng dịch vụ bên thứ 3 của VN (VNPay/MoMo/GHN...).

## 9. Rủi ro chính & Phương án giảm thiểu

| Rủi ro | Mức | Giảm thiểu |
|---|---|---|
| Team nhỏ vận hành nhiều service | Cao | CI/CD chuẩn, template service, ranh giới rõ |
| Distributed transaction (saga) phức tạp | Cao | Outbox pattern, test lỗi từng bước (Toxiproxy) |
| Peak vượt dự kiến | TB | Load test trước chiến dịch, autoscaling, kill-switch |
| Tích hợp bên thứ 3 không ổn định | TB | Webhook + retry + circuit breaker; fallback COD |

## 10. Phê duyệt

| Người | Vai trò | Quyết định |
|---|---|---|
| (Chưa điền) | Chủ dự án | ☐ Đã duyệt |
| (Chưa điền) | Tech Lead | ☐ Đã duyệt |
