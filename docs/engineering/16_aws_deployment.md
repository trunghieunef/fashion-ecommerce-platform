# 16 — AWS staging, ngân sách và CI/CD

B1 bổ sung · 2026-09-19 · Owner DEVOPS + TL; PO duyệt chi phí. AWS được chủ dự án chọn, có $200 credit theo thông tin cung cấp. Chưa đăng nhập/kiểm chứng Billing, chưa tạo tài nguyên.

Danh mục và bộ version R1 sau research ở [17 Tech Stack](17_tech_stack.md); chưa có runtime compatibility hoặc image digest được kiểm thử. Tài liệu 16 sở hữu topology AWS, ngân sách và quy trình deploy.

**ADR-18:** AWS/cloud nằm ngoài Sprint 1. Trong Sprint 1 chỉ dùng local workflow ở 09/10; không tạo CloudFormation stack, IAM role, ECR, EC2, K3s hoặc ArgoCD. Tài liệu này là kế hoạch cho giai đoạn sau S1-local và không phải checklist phải hoàn thành trong Sprint 1.

## 1. Đã có gì và chưa có gì?

| Hạng mục | Trạng thái thực tế |
|---|---|
| CI tài liệu | Có cấu hình [GitHub Actions](../../.github/workflows/docs-ci.yml), checker và test chạy local; cần push và xem run để xác nhận trên GitHub |
| CI ứng dụng | Chưa có; repo chưa có service, frontend, Dockerfile, migration hoặc test nghiệp vụ |
| CD / hạ tầng AWS | Chưa có workflow triển khai, CloudFormation hay manifest chạy được; tài liệu này là kế hoạch thực hiện PLT-04 |
| Cloud provider | AWS đã chọn; region/topology dưới đây là đề xuất chưa provision |
| Credit / tài khoản | Chủ dự án báo có $200; loại plan, số dư khả dụng, dịch vụ áp dụng và ngày hết hạn cần kiểm tra Billing |
| Production | Chưa được duyệt; staging một máy không đáp ứng mặc nhiên SLO/HA/RPO/RTO |

CI xanh ở thời điểm này chỉ là kiểm tra tài liệu, không phải ứng dụng build/test/deploy thành công. Không đánh dấu PLT-04 hay G0 Done từ kết quả đó.

## 2. Phương án đề xuất cho khoản credit

Khởi đầu bằng **một EC2 Linux + K3s single-node cho staging**, chỉ chạy khi nhóm cần demo/test. Giữ Kubernetes/GitOps theo ADR-12; chưa dùng EKS. EKS standard support có phí control plane $0,10/giờ, tương đương $73 với 730 giờ, chưa gồm worker, lưu trữ và mạng. [AWS EKS pricing](https://aws.amazon.com/eks/pricing/).

| Thành phần | Cấu hình đề xuất / giới hạn |
|---|---|
| Region | Singapore `ap-southeast-1` để nhóm đánh giá latency với người dùng VN; xác nhận giá, dịch vụ được phép và yêu cầu dữ liệu trước tạo |
| Compute | EC2 On-Demand x86, bắt đầu đánh giá `t3.large` 2 vCPU / 8 GiB cho một service mẫu và dependencies cần dùng; không mua Reserved/Savings Plan |
| Kubernetes | K3s một server/worker; phiên bản ứng viên theo R1 tại 17, pin sau compatibility O02; không có HA; control-plane cùng failure domain với workload |
| Storage | Đề xuất 40 GiB gp3 mã hóa; đo dung lượng/IOPS và đặt retention trước tăng. Persistent volumes không được phụ thuộc filesystem container |
| Network | VPC, một public subnet, Internet Gateway; EC2 public IPv4 nhưng Security Group chỉ mở ingress đã duyệt. Không NAT Gateway, ALB hoặc EKS trong bản thử nghiệm |
| Application ingress | HTTPS tại ingress; domain/TLS hợp lệ trước callback sandbox. Public chỉ storefront/public gateway và callback cần thiết; chặn `/internal` |
| Frontend | React/Vite SPA theo ADR-17; deploy static artifact, không Node runtime. Dùng static container hoặc S3 private + CDN sau duyệt chi phí; pin server image khi hiện thực. SPA fallback không được rewrite API/callback hoặc che lỗi asset |
| Quản trị | Ưu tiên SSM với instance role giới hạn; không SSH/6443/DB/Kafka/Redis/Nacos/ArgoCD mở Internet. Không cấp quyền AWS của node cho pod không tin cậy |
| Data | Một PostgreSQL instance, database/credential riêng mỗi service. Redis, Kafka single-node chỉ thêm khi service mẫu cần; không bỏ Kafka của các flow yêu cầu outbox/event |
| Nacos | Chốt compatibility và metadata/persistence theo O02; standalone cho staging nếu version hỗ trợ; không giả định dùng PostgreSQL nghiệp vụ làm metadata store |
| Registry / backup | ECR private cho image; S3 private cho backup mã hóa, lifecycle theo policy; không public bucket, không backup duy nhất trên EC2 |
| Observability | Health, logs có rotation, CPU/RAM/disk và cảnh báo cơ bản từ đầu. Bộ Prometheus/Grafana/Loki/Tempo triển khai dần trong PLT-05 sau khi đo tài nguyên |
| CD đích | ArgoCD pull manifests từ Git; giới hạn namespace và repository/path, không public dashboard. Chi phí RAM của ArgoCD phải tính trong sizing |

K3s yêu cầu tối thiểu server 2 cores / 2 GB RAM **chưa gồm ứng dụng**. 8 GiB không được coi là đủ cho 8 service MVP + gateway + Kafka/Nacos/DB + observability. PLT-04 phải đo cả rollout: requests/limits, JVM heap và native memory, DB pool, disk, pod mới/cũ cùng chạy. Nếu OOM hoặc không còn khoảng dự phòng, giảm phạm vi demo hoặc xin duyệt sizing; không tự mua máy lớn. [K3s requirements](https://docs.k3s.io/installation/requirements).

Giới hạn có chủ ý: một máy là điểm lỗi duy nhất; phù hợp học/dev/staging synthetic. Khi cần toàn bộ MVP hoặc production, review lại compute, managed DB/PITR, failure domains, bảo mật, khả năng trực vận hành và tổng giá. Sơ đồ DEP-01 trong 07 là topology tham chiếu tương lai, không phải tài nguyên đã mua bằng credit này.

## 3. Credit và ngân sách

### 3.1. Xác nhận tài khoản trước provision

PO kiểm tra Console → Billing and Cost Management → Credits và phần Account plan; ghi **không kèm secret**:

| Thông tin | Giá trị hiện biết |
|---|---|
| Plan Free/Paid | Chưa xác nhận |
| Chương trình credit, số dư khả dụng | $200 theo chủ dự án; chưa kiểm chứng số dư/điều kiện |
| Ngày hết hạn plan và từng credit | Chưa xác nhận |
| Region / instance type được phép / quota | Chưa xác nhận |
| Email nhận alert, người xử lý | Chưa gán |
| Ngân sách chi phí gộp/tháng, khoản tiền tự trả được phép | Chưa duyệt; không suy $200 là ngân sách hằng tháng |

Theo Free Tier hiện hành, credit tối đa $200 không đồng nghĩa mọi tài khoản đã nhận đủ. Free Plan kết thúc khi đủ 6 tháng hoặc hết credit, tùy điều kiện nào đến trước; credit Free Tier có hạn 12 tháng từ lúc tạo tài khoản. Paid Plan có thể phát sinh khoản ngoài credit. Điều khoản Billing thực tế của tài khoản là căn cứ; không tự nâng plan, tạo/join Organizations hoặc Control Tower vì có thể làm mất Free Tier credit. [AWS Free Tier FAQ](https://aws.amazon.com/free/free-tier-faqs/).

### 3.2. Mẫu dự toán trước khi bấm Create

Đề xuất quản trị: nếu xác nhận còn đủ $200, dành $50 dự phòng và lập kế hoạch phần $150 còn lại, mục tiêu ban đầu **không quá $50 chi phí gộp/tháng**. Đây là mục tiêu cần PO duyệt và đối chiếu estimate, không phải báo giá hay cơ chế chặn phí. Không cam kết chạy được ba tháng khi chưa biết workload và hạn credit.

DEVOPS lưu estimate từ [AWS Pricing Calculator](https://calculator.aws/) với region, Linux On-Demand, ngày lập và các dòng sau; không dùng giá US East để khẳng định giá Singapore:

| Khoản | Cách tính / bằng chứng cần ghi |
|---|---|
| EC2 | Đơn giá đúng region × giờ chạy: profile demo 8 giờ × 20 ngày = 160 giờ; so thêm profile 24/7 = 730 giờ |
| CPU burst | T3 credit mode phải ghi rõ; nếu Unlimited, tính cả khả năng surplus CPU charge; nếu Standard, test tác động throttling |
| EBS | 40 GiB × giá gp3/tháng + IOPS/throughput mua thêm nếu có; vẫn tính khi EC2 stop |
| Public IPv4 | $0,005 × giờ giữ địa chỉ; một EIP giữ cả tháng 730 giờ = $3,65, kể cả khi idle |
| ECR | Dung lượng image × giá lưu trữ, cộng transfer/scan nếu bật tính phí; retention phải giữ digest đang dùng và rollback |
| S3 / snapshots | Dung lượng, requests, snapshot tăng dần, restore/download; không coi backup là miễn phí |
| Logs / monitoring | Ingest, retention, metrics/alarms và queries theo dịch vụ thực bật |
| Network / DNS | Data egress, cross-AZ nếu có, hosted zone/domain nếu mua; chứng chỉ và CDN theo lựa chọn thực tế |
| Tổng | Compute + storage + network + registry + backup + observability + thuế/phí không được credit bù nếu có; ghi cả gross và credit áp dụng |

Nguồn kiểm tra: [EC2 T3 và CPU credits](https://aws.amazon.com/ec2/instance-types/t3/), [VPC public IPv4 pricing](https://aws.amazon.com/vpc/pricing/), [EBS pricing](https://aws.amazon.com/ebs/pricing/). EC2 stop không xóa EBS/EIP/snapshots; phải kiểm kê chi phí còn giữ, không chỉ xem trạng thái máy.

### 3.3. Cảnh báo và xử lý

- Trước provision: MFA root, không root access key; budget email đã confirm và owner chịu trách nhiệm.
- Đề xuất budget tháng $50, cảnh báo actual 50%/80%/100% và forecast 100%; theo dõi chi phí **trước khi trừ credit**, kiểm tra cấu hình Include credits/refunds, đồng thời xem số dư credit riêng để tránh bill $0 che mức tiêu thụ.
- Theo dõi toàn tài khoản, không chỉ tag staging, để thấy phí tài nguyên tạo nhầm. Tag `Project=fashion-ecommerce`, `Environment=staging`, `Owner=<người phụ trách>`, `ExpiresOn=<ngày review>` cho tài nguyên hỗ trợ tag.
- 80%: owner kiểm tra dịch vụ/region/disk/logs/CPU burst, tạm dừng thử nghiệm không cần. 100% hoặc credit còn dưới dự phòng: ngừng tạo tài nguyên mới; PO quyết định có tiếp tục, không tự nâng ngân sách.
- Budget là cảnh báo có độ trễ, không phải hard spending cap. Không tự động terminate máy hay xóa database khi nhận email; cần quy trình bảo toàn dữ liệu. [AWS Budgets](https://docs.aws.amazon.com/cost-management/latest/userguide/budgets-managing-costs.html), [cấu hình cost budget](https://docs.aws.amazon.com/cost-management/latest/userguide/create-cost-budget.html).

## 4. CI/CD theo mức trưởng thành

### 4.1. Có thể dùng ngay: CI tài liệu

Workflow `Documentation CI`, job `docs-check`: push/PR hoặc chạy tay → checkout read-only → test checker → kiểm tra README và docs. Không cần AWS credential, không publish artifact, không deploy. GitHub Actions có quota/chi phí riêng, không trừ từ AWS credit; repo owner kiểm tra Actions billing trước bật.

Chạy từ repo root với Python 3.10+:

```bash
python3 -B -m unittest discover -s scripts -p 'test_*.py' -v
python3 -B scripts/check_docs.py
```

Checker kiểm tra code fence đóng/mở, JSON syntax trong fence `json`, đường dẫn inline link nội bộ. Chưa kiểm tra anchor, reference-style link, URL ngoài, render Mermaid, schema nghiệp vụ hoặc độ nhất quán PRD. Các phần đó vẫn cần review/PLT-02. Sau khi push và có run pass, repo admin mới đặt `docs-check` làm required check qua ruleset/branch protection; không coi workflow tự bảo vệ branch.

### 4.2. Khi PLT-01 có service và frontend thật

1. PR: docs + lint/unit + PostgreSQL migration/integration + API/event compatibility + secret/dependency/image scan. Payment/stock cần race/failure tests tương ứng 11. Không cấp deploy secret hoặc AWS role cho PR từ fork.
2. Merge nhánh chính được bảo vệ: chạy test, build image từ commit đã test, tag commit SHA, push ECR và lưu digest. Build một lần, promotion dùng cùng digest; không dùng `latest` làm release identity.
3. GitHub → AWS qua OIDC session ngắn hạn. IAM role chỉ được push repository ECR cần dùng; trust khóa `aud=sts.amazonaws.com` và `sub` đúng org/repo/branch hoặc protected environment. Role build không có quyền tạo EC2/xóa DB. Không lưu access key dài hạn. [GitHub OIDC với AWS](https://docs.github.com/en/actions/how-tos/secure-your-work/security-harden-deployments/oidc-in-aws).
4. Cập nhật digest trong manifests bằng PR riêng có reviewer. Quyền bot mở PR tách khỏi job PR read-only; không tự sửa/bỏ qua ruleset. ArgoCD đọc Git, reconcile staging sau merge; không cần đưa kubeconfig cluster-admin vào GitHub.
5. Cấu hình node pull ECR bằng cơ chế cấp/refresh credential được hỗ trợ và kiểm thử sau khi token cũ hết hạn. Không coi một lần `docker login` hoặc một Secret token tĩnh là giải pháp lâu dài.
6. Migration có job/lock và thứ tự expand-compatible → app → smoke. CD giới hạn một rollout/môi trường; lỗi readiness/migration/smoke chặn promotion, giữ log evidence đã redacted.
7. Smoke: health + route public/internal + một luồng của service hiện có; khi đủ G1 chạy COD xuyên suốt, đủ G2 chạy online sandbox/refund. Chưa có endpoint thì chưa khai báo smoke đã pass.
8. Production: gate G2, budget/topology riêng, secrets/providers riêng, người phê duyệt và deploy window. Không tự promote production khi staging xanh.

Rollback GitOps: revert PR digest/manifests về bản đã xác minh tương thích schema, ArgoCD reconcile, chạy lại smoke. Không chỉ sửa live Deployment vì ArgoCD sẽ kéo về Git. Không tự rollback schema phá dữ liệu; theo runbook 13.

## 5. Chia PLT-04 thành việc có thể giao

Đây là subtasks của PLT-04, không cộng lần hai vào tổng 51 work package của 09/10. Assignee/reviewer cụ thể chưa gán; parent vẫn Planned. DEVOPS/TL estimate lại sau compatibility và sizing spike.

| Subtask | Owner / phụ thuộc | Đầu ra và acceptance |
|---|---|---|
| PLT-04.A — credit & guardrails | PO + DEVOPS; O01 | Ghi plan/số dư/hạn, estimate đúng region, ngân sách và owner alert; test email, MFA, không nâng plan ngoài phê duyệt |
| PLT-04.B — CI tài liệu | DEVOPS; độc lập phần app | Các file CI/checker đã bổ sung; còn push, run GitHub và ruleset có evidence; CI không có quyền AWS |
| PLT-04.C — hạ tầng staging | DEVOPS; A, PLT-01, SEC-01 | CloudFormation cho tài nguyên cần dùng, change set được review; pin AMI/K3s, IAM/SG/volume mã hóa; chỉ execute sau phê duyệt chi phí; drift/recreate có hướng dẫn |
| PLT-04.D — image supply chain | DEVOPS + BE; C, service mẫu PLT-01 | Dockerfile, tests/build, OIDC/ECR least privilege, image digest/scan, node pull sau token expiry; fork PR không có deploy quyền |
| PLT-04.E — manifests & GitOps | DEVOPS + BE; D | Namespace, secrets injection, resource requests/limits, probes, DB credential riêng, migration, ArgoCD scoped; HTTPS và `/internal` không public |
| PLT-04.F — proof & handoff | DEVOPS + QA; E, PLT-05 cho observability | Fresh deploy + smoke + app rollback + stop/start/recovery; lưu CPU/RAM/disk và chi phí; runbook/URL/access handoff; G0 review bởi TL |

PLT-04.C dùng CloudFormation native để tránh thêm công cụ IaC khi chưa có công cụ sẵn; template thật chỉ viết sau khi khóa đầu vào A/O02. Không chạy installer không pin version/checksum hoặc copy kubeconfig/secrets vào repo. OPS-01 vẫn sở hữu backup/PITR/restore drill đầy đủ; task F không thay acceptance OPS-01.

## 6. Checklist lần triển khai AWS đầu tiên

- [ ] A đã duyệt; không có tài nguyên được tạo từ tài khoản/region không rõ.
- [ ] Service mẫu, Dockerfile, migration và health/smoke có lệnh thực đã test.
- [ ] CloudFormation change set liệt kê chính xác tài nguyên/ước tính; policy retain dữ liệu được review.
- [ ] Domain/TLS/callback sandbox đã chốt; không expose DB/Kafka/Redis/Nacos/ArgoCD/Kubernetes API.
- [ ] Secret được cấp qua kênh bảo mật, mã hóa at rest; backup access tách app; không secrets trong Git/log/user data/template parameters hiển thị.
- [ ] Requests/limits và rollout fit trong node; đo thực, không giả định 8 GiB chạy toàn bộ hệ thống.
- [ ] Deploy bằng digest, migrations pass, public/internal smoke pass; ghi URL, build, ngày và reviewer.
- [ ] Reboot/stop-start test, địa chỉ/DNS, mounts, DB recovery, saga deadlines và credential refresh được kiểm tra.
- [ ] App rollback diễn tập; bản backup restore được xác minh trước khi lưu dữ liệu cần giữ.
- [ ] Billing sau khoảng sử dụng đầu tiên được kiểm tra; có lịch review hằng tuần và trước hạn credit.

### Dừng và thu hồi staging

Chỉ dùng synthetic data và sandbox; lên lịch thông báo nhóm trước dừng vì callback/retry sẽ gián đoạn. Pause checkout mới, xử lý hoặc ghi nhận obligations, backup và xác minh khả năng khôi phục; rồi stop đúng instance ID đã đối chiếu account/region/tag. Khi khởi động lại, kiểm tra DNS/TLS, dependencies, deadline/recovery và payment sandbox trước mở traffic.

Stop không phải terminate. Muốn thu hồi: xuất/restore-check dữ liệu cần giữ, review chính xác stack/resources và retention, phê duyệt rồi mới xóa. Kiểm kê EBS, EIP, snapshots, ECR, S3, log groups và DNS còn tính phí; tài nguyên retained không tự mất phí sau xóa stack. Không xóa bucket/volume/stack theo wildcard hoặc chỉ vì thấy budget vượt ngưỡng.

## 7. Điều kiện để tiếp tục provision

Không provision trong Sprint 1. Sau khi S1-local được nghiệm thu, vẫn còn thiếu: plan/hạn credit, ngân sách được duyệt, người nhận alert, quyền AWS theo kênh an toàn, region/domain và mã ứng dụng. Có thể hoàn thành PLT-01/02/SEC-01 local mà không cần AWS. Không gửi access key, secret, mật khẩu root hay mã MFA qua chat.
