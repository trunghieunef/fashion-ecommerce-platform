# Mẫu dự toán chi phí AWS staging — PLT-04.A

`TASK:PLT-04.A` · REQ: NFR-01/06 · dependency: O01 (PO + DEVOPS). Điền trước khi tạo change set,
theo [16 §3](../engineering/16_aws_deployment.md). **Chưa điền**: giá phải lấy từ
[AWS Pricing Calculator](https://calculator.aws/) cho đúng region, Linux On-Demand, kèm ngày lập.
Không dùng giá US East, không đoán số và không ghi secret hay account ID đầy đủ.

| Trường | Giá trị |
|---|---|
| Người lập / ngày | |
| Region | (đề xuất `ap-southeast-1`, chờ O01) |
| Account alias (không ghi ID đầy đủ) | |
| Plan Free/Paid, số dư credit, ngày hết hạn | |
| Link estimate đã lưu | |

| Khoản | Cấu hình | Profile demo 160 giờ/tháng | Profile 24/7 730 giờ/tháng | Ghi chú |
|---|---|---|---|---|
| EC2 | `t3.large`, CPU credit mode: | | | Unlimited thì tính cả surplus CPU charge |
| EBS gp3 | 40 GiB, IOPS/throughput mặc định | | | Vẫn tính khi EC2 stop |
| Public IPv4 / EIP | 1 địa chỉ | | | Tính theo giờ giữ địa chỉ, kể cả khi idle |
| ECR | 3 repository, tối đa 20 image mỗi repo | | | Dung lượng × giá lưu trữ, cộng transfer |
| S3 backup / snapshot | | | | Dung lượng, request, restore |
| Logs / monitoring | | | | Theo dịch vụ thực bật |
| Network / DNS / domain | | | | Egress, hosted zone, domain nếu mua |
| Thuế/phí không được credit bù | | | | |
| **Tổng gross** | | | | Ghi cả gross và phần credit áp dụng |

| Phê duyệt | Giá trị |
|---|---|
| Ngân sách tháng được duyệt (đề xuất $50) | |
| Email nhận alert 50%/80%/100% + forecast | |
| PO duyệt (tên, ngày) | |
| Change set đã review (stack, tên change set, reviewer) | |
