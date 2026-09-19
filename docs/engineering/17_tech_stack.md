# 17 — Tech Stack và quản lý phiên bản

B1 · Hồ sơ lựa chọn R1 · Research ngày 2026-09-19 · Owner TL + DEVOPS; FE/BE/QA review phần phụ trách.

## 1. Kết luận và mức độ xác minh

Chọn **Java 21 LTS + Spring Boot 4.0**, **React + Vite SPA**, **PostgreSQL 17**, Kafka KRaft và Redis 8.2. Giữ kiến trúc service/Gateway/Nacos hiện có; staging AWS theo phương án EC2 + K3s trong [16](16_aws_deployment.md).

Tiêu chí: còn đường cập nhật, phối hợp được theo BOM/peer dependencies của upstream, ít runtime/tooling phải tự vận hành, phù hợp nhóm nhỏ và không tự tăng chi phí cloud. Không chọn mọi thành phần theo bản mới nhất.

Đây là **bộ phiên bản được chọn để hiện thực và kiểm thử**, không phải bộ đã được dự án kiểm thử. Repo vẫn chỉ có tài liệu và CI tài liệu; chưa có ứng dụng, dependency lockfile, image digest hoặc deployment. Nguồn chính thức bên dưới được đối chiếu ngày 2026-09-19; kiểm tra lại release/security advisories khi tạo lockfile và trước mỗi release.

| Mức bằng chứng | Trạng thái R1 |
|---|---|
| Release tồn tại, version/support/peer mapping theo upstream | Đã research; dẫn nguồn tại từng nhóm |
| Tổ hợp exact patches chạy cùng nhau trong repo | **Chưa kiểm thử**; PLT-01 thực hiện §7 |
| Lockfile, dependency tree, image digest và scan | Chưa có |
| CI ứng dụng, staging, performance, restore và production | Chưa có; docs CI không thay các bằng chứng này |

Thay định hướng Boot 3 bằng Boot 4 và chọn frontend toolchain được ghi ở ADR-16/17 trong [08](../design/08_decisions.md). O02 vẫn mở để nghiệm thu compatibility; không coi R1 là phê duyệt production hay quyền sử dụng AWS credit.

## 2. Backend: nâng theo tổ hợp, không nâng rời từng thư viện

| Thành phần | Phiên bản chọn | Cách dùng / lý do |
|---|---|---|
| Eclipse Temurin JDK | **21.0.12.1+1**, dòng 21 LTS | Giữ Java 21 của baseline; dùng security patch đã phát hành, chưa cần chuyển Java 25 |
| Spring Boot | **4.0.8** | Khởi đầu mới trên dòng 4.0; không chọn 4.1 trước mapping Alibaba |
| Spring Cloud BOM | **2025.1.3** | Quản lý Gateway và các module Cloud dùng thật |
| Spring Cloud Alibaba BOM | **2025.1.0.0** | Tích hợp Nacos; không cài toàn bộ starter của Alibaba |
| Spring Cloud Gateway | **5.0.3**, qua Cloud BOM | Gateway riêng; không override version tại service |
| Nacos server | **3.2.4** | Bản vá server; private network, bật auth, lưu metadata riêng |
| Nacos Java client | **3.1.1**, qua Alibaba BOM | Không ép client bằng server version |
| Apache Maven | **3.9.16** | Maven Wrapper là entry point build; không phụ thuộc Maven cài tùy ý trên máy |

Nguồn: [Temurin security release](https://adoptium.net/news/2026/09/eclipse-temurin-8u504-110321-170201-210121-25041-26021-available), [Temurin support](https://adoptium.net/support/), [Boot managed dependencies](https://docs.spring.io/spring-boot/4.0/appendix/dependency-versions/coordinates.html), [Cloud 2025.1.3](https://github.com/spring-cloud/spring-cloud-release/releases/tag/v2025.1.3), [Alibaba version mapping](https://sca.aliyun.com/docs/2025.x/overview/version-explain/), [Nacos 3.2.4](https://github.com/alibaba/nacos/releases/tag/3.2.4), [Maven download](https://maven.apache.org/download.cgi).

### 2.1. Vì sao đổi Boot 3, nhưng chưa lên Boot 4.1?

Bảng [Spring Cloud](https://spring.io/projects/spring-cloud/) đánh dấu train 2025.0.x/Boot 3.5.x và các train Boot 3 cũ hơn đã hết hỗ trợ. Dự án chưa có code Boot 3 cần bảo toàn nên chọn dòng mới để tránh bắt đầu với một đợt migration đã nhìn thấy trước.

Alibaba 2025.1.0.0 công bố tổ hợp tham chiếu Cloud 2025.1.0, Boot 4.0.0 và client Nacos 3.1.1. R1 chọn patch mới hơn **trong cùng dòng** Boot 4.0/Cloud 2025.1. Đây là suy luận lựa chọn từ mapping, **không phải tuyên bố upstream đã test chính xác 4.0.8 + 2025.1.3 + Alibaba 2025.1.0.0**. Phải resolve dependency tree và chạy spike; không tắt compatibility verifier để làm test pass.

[Nacos upgrade manual](https://nacos.io/en/docs/latest/manual/admin/upgrading/) ghi server 3.2.x hỗ trợ client 3.x; đồng thời thay đổi API cũ và yêu cầu auth của server. Vẫn phải test client 3.1.1 với server 3.2.4, config import, login/permissions, reconnect và restart. Không thêm legacy API adapter chỉ để giữ ví dụ cấu hình cũ.

### 2.2. Quy ước implementation

- Service nghiệp vụ: Spring MVC + JDBC/JdbcClient + transaction của Spring; SQL migration và locking tường minh theo 05/06. Không thêm ORM hoặc framework saga trước nhu cầu thực.
- Gateway: dùng starter Gateway Server WebFlux của Cloud; không đưa JDBC/JPA vào gateway, không trộn MVC starter vào ứng dụng gateway.
- HTTP giữa service/provider: dùng RestClient của Spring ở service MVC; timeout bắt buộc, retry mutation chỉ theo idempotency/recovery ở 06. Không thêm OpenFeign chỉ để gọi REST.
- Flyway, JDBC driver, Jackson, Spring Security, Redis client, Micrometer và test libraries theo Boot BOM. Không pin override transitive mặc định; giữ effective POM/dependency tree làm bằng chứng.
- Config Nacos theo `spring.config.import`; không copy mẫu `bootstrap.yml` cũ. [Alibaba release notes](https://github.com/alibaba/spring-cloud-alibaba/releases/tag/2025.1.0.0).
- Nacos discovery/config phục vụ ứng dụng; Kubernetes quản lý pod/service/network. PLT-01 chọn một đường discovery cho mỗi caller, không tự bật hai cơ chế cạnh tranh.
- Local/staging đánh giá Nacos standalone với embedded store và volume riêng; phải có restart/backup proof. Không mặc định dùng PostgreSQL nghiệp vụ làm metadata store. HA/metadata store production vẫn thuộc PLT-04/O01. [Standalone quick start](https://nacos.io/en/docs/v3.1/quickstart/quick-start/) là tham khảo cơ chế, không thay test trên 3.2.4.

## 3. Database, messaging và cache

| Thành phần | Phiên bản chọn | Lý do / giới hạn |
|---|---|---|
| PostgreSQL | **17.11** | Dòng 17 đã có nhiều bản vá, hỗ trợ tới 2029-11-08; chưa có nhu cầu bắt buộc dùng 18 |
| PostgreSQL JDBC | **42.7.13**, qua Boot BOM | Không chọn driver độc lập khỏi bộ backend |
| Flyway core + PostgreSQL module | **11.14.1**, qua Boot BOM | Migration từng service; cần cả module database PostgreSQL |
| Apache Kafka broker | **4.1.2**, KRaft | Cùng dòng client BOM; không thêm ZooKeeper |
| Kafka client / Spring Kafka | **4.1.2 / 4.0.7**, qua Boot BOM | Test retry, duplicate và restart với broker thật |
| Redis Open Source | **8.2.9** | Dòng 8.2 Extended; chỉ cache/rate limit/auth ephemeral |

Nguồn: [PostgreSQL versioning](https://www.postgresql.org/support/versioning/), [Boot BOM](https://docs.spring.io/spring-boot/4.0/appendix/dependency-versions/coordinates.html), [Kafka downloads](https://kafka.apache.org/community/downloads/), [Redis 8.2 release notes](https://redis.io/docs/latest/operate/oss_and_stack/stack-with-enterprise/release-notes/redisce/redisos-8.2-release-notes/), [Redis version policy](https://redis.io/docs/latest/operate/oss_and_stack/install/version-mgmt/).

Redis 8 có các lựa chọn license AGPLv3/RSALv2/SSPLv1; không được ghi Redis 8 là BSD. PO/TL phải xác nhận license phù hợp cách phân phối/vận hành trước release; R1 không phải kết luận pháp lý. [Redis licenses](https://redis.io/legal/licenses/).

Giữ các invariant của [04](../design/04_architecture.md), [05](../design/05_database_design.md), [06](../design/06_service_flows.md):

- Database/credential riêng từng service, có thể dùng chung một PostgreSQL instance; không query chéo DB.
- DB là nguồn sự thật của tiền, kho và cart. Redis outage không được làm mất dữ liệu authoritative.
- Kafka không thay outbox/inbox, dedupe trong transaction và recovery worker. KRaft combined single-node chỉ là profile local/staging, không phải production HA.
- Không thêm Redis Stack modules, Elasticsearch hoặc database thứ hai khi chưa có task chứng minh nhu cầu.

## 4. Frontend: một toolchain, triển khai file tĩnh

| Thành phần | Phiên bản chọn | Quy ước |
|---|---|---|
| Node.js | **24.21.0 LTS** | Chỉ cần cho dev/build/test của SPA, không cần Node server production |
| npm | **11.19.0**, bản đi cùng Node trên | Một package manager; npm workspaces cho storefront/admin khi tạo code |
| React + react-dom | **19.2.8**, đồng phiên bản | Chọn patch của dòng 19.2, chưa cần tính năng 19.3 |
| TypeScript | **6.0.3** | Chưa chuyển compiler native của major 7 trong lần bootstrap này |
| Vite | **8.3.0** | Build SPA cho cả storefront và admin |
| @vitejs/plugin-react | **6.1.1** | Plugin chính thức cho Vite 8; chưa bật React Compiler thử nghiệm |
| React Router | **8.4.0**, Data Mode | Routing phía client; không dùng server/framework mode mặc định |
| CSS, state, HTTP, format | CSS Modules/native CSS; React state/context; fetch; Intl | Chưa cần Tailwind/Redux/Axios; chỉ thêm khi có nhu cầu cụ thể |

Nguồn: [Node 24.21.0](https://nodejs.org/en/blog/release/v24.21.0), [npm đi kèm Node](https://raw.githubusercontent.com/nodejs/node/v24.21.0/deps/npm/package.json), [React releases](https://github.com/facebook/react/releases), [TypeScript releases](https://github.com/microsoft/TypeScript/releases), [Vite 8.3.0 package](https://raw.githubusercontent.com/vitejs/vite/v8.3.0/packages/vite/package.json), [React plugin release](https://github.com/vitejs/vite-plugin-react/releases/tag/plugin-react%406.1.1), [Router 8.4.0 package](https://raw.githubusercontent.com/remix-run/react-router/react-router%408.4.0/packages/react-router/package.json).

Đã đối chiếu khai báo: Vite/plugin nhận Node 24; Router 8.4 yêu cầu React >=19.2.7 và Node >=22.22.0. Các ràng buộc này khớp lựa chọn, nhưng chưa chứng minh clean install/typecheck/build/browser tests pass. Các gói `@types/*`, lint và dependency phụ phải chọn cùng template, khóa trong package-lock và test ở PLT-01; không đoán patch từ runtime version.

### 4.1. Hosting và đánh đổi SEO

Build ra static assets, hosting qua HTTPS static server hoặc S3 private + CDN theo lựa chọn/budget ở 16. Staging có thể dùng static container đã được pin khi PLT-04 triển khai. Không chạy Vite dev server/`vite preview` như production server. Cấu hình SPA fallback cho route UI, **không** rewrite API/callback/assets lỗi thành `index.html`.

**Giả định để chọn SPA:** MVP chưa có acceptance bắt buộc HTML sản phẩm render từ server, SEO organic hoặc social preview theo từng sản phẩm. CSR có hạn chế ở các mặt này. PO/FE cần xác nhận trước WEB-01; nếu SEO là điều kiện ra mắt, mở lại ADR-17 và chọn SSR/prerender cho storefront trước khi làm routing/catalog. Admin vẫn không cần SSR. Không hứa chuyển SPA sang SSR sẽ không tốn công.

Không chọn Next.js mặc định vì mục tiêu hiện tại là static hosting và backend Java đã riêng; Next SSR sẽ thêm runtime Node, cache và sizing cần vận hành. Đây là lựa chọn cho constraints của dự án, không phải nhận định Next.js kém hơn.

VI/EN, accessibility, validation form, token in-memory/refresh cookie và UI states vẫn phải đáp ứng [14](../design/14_frontend_behavior.md). Frontend gọi Gateway; không chuyển nghiệp vụ tiền/kho hoặc quyết định quyền sang client.

## 5. Local, AWS và observability

| Thành phần | Phiên bản chọn | Phạm vi |
|---|---|---|
| OS staging | **Ubuntu 24.04 LTS**, cập nhật security patches | AMI ID theo region được khóa lúc provision, không đoán trước |
| Docker Engine / Compose | **29.8.1 / 5.5.1** | Linux local/CI container tooling; Docker Desktop có vòng đời/license riêng |
| K3s | **v1.35.8+k3s1** | Kubernetes **1.35.8**; dùng containerd/ingress đi kèm, không nâng rời bundle |
| Argo CD | **3.5.3** | Dòng 3.5 có Kubernetes 1.35 trong bảng upstream test |
| Prometheus | **3.13.3 LTS** | Ưu tiên LTS thay vì bản feature 3.14 |
| Grafana OSS | **13.2.2** | Dashboard private, provision datasource/dashboard bằng Git |
| Loki | **3.7.8** | Logs JSON có masking, giới hạn retention/disk |
| Tempo | **3.0.3** | Đánh giá monolithic ở PLT-05; không mặc định dựng distributed tracing cluster |
| OpenTelemetry / Micrometer | Theo Boot BOM | Instrumentation application; không tự pin Java agent/collector chưa cần |
| IaC / manifests / CI | CloudFormation + Kustomize qua kubectl + GitHub Actions | Không thêm Terraform/Helm chỉ để có thêm công cụ |
| Docs checker | Python **3.10+**, stdlib | Có code thật; runner `ubuntu-24.04` hiện không pin exact Python patch |

Nguồn: [Docker Engine](https://docs.docker.com/engine/release-notes/29/), [Compose releases](https://github.com/docker/compose/releases), [K3s 1.35 releases](https://docs.k3s.io/release-notes/v1.35.X), [Argo 3.5.3](https://github.com/argoproj/argo-cd/releases/tag/v3.5.3), [Argo tested Kubernetes versions](https://argo-cd.readthedocs.io/en/stable/operator-manual/installation/), [Prometheus LTS download](https://prometheus.io/download/), [Grafana](https://github.com/grafana/grafana/releases/tag/v13.2.2), [Loki](https://github.com/grafana/loki/releases/tag/v3.7.8), [Tempo](https://github.com/grafana/tempo/releases/tag/v3.0.3).

Tempo 3 phân biệt monolithic và microservices mode; tài liệu deployment-modes ghi monolithic không cần Kafka. Không dùng cấu hình Tempo 2 hoặc suy diễn mọi mode có cùng topology. PLT-05 phải kiểm tra config, OTLP ingest/query và tài nguyên trên version đã pin. [Tempo deployment modes](https://grafana.com/docs/tempo/latest/reference-tempo-architecture/deployment-modes/).

Các phiên bản observability là lựa chọn cho PLT-05, **không có nghĩa cài tất cả vào node 8 GiB**. Log collector/agent và exporter bổ sung chỉ khóa khi xác định pipeline thực tế. Trước mắt không thêm collector trung gian nếu app export OTLP trực tiếp đáp ứng được yêu cầu; vẫn phải có log collection để Loki nhận dữ liệu khi triển khai.

AWS credit $200 không thay approval chi phí. Không tự provision, không chuyển sang EKS/RDS/NAT Gateway. K3s dùng containerd, không cần cài Docker daemon trên EC2 chỉ để chạy pod. Profile 8 GiB chỉ thử service mẫu + dependency cần dùng; toàn MVP/HA phải đo và duyệt sizing riêng theo 16.

## 6. Test stack và các công cụ không khóa quá sớm

| Lớp | Lựa chọn | Bằng chứng cần có |
|---|---|---|
| Backend unit | JUnit Jupiter **6.0.3**, qua Boot BOM | Rules, validation, state transitions |
| DB/broker integration | Testcontainers **2.0.5**, qua Boot BOM | PostgreSQL/Kafka/Redis thật theo version R1; không H2 thay PostgreSQL |
| FE unit | Vitest **5.0.1** | Pure logic và client states; Vite 8/Node 24 nằm trong khai báo hỗ trợ |
| Browser/UI E2E | Playwright **1.63.0** | Browser binaries/container đồng bản; login, deep link, VI/EN, checkout và lỗi |
| Docs | Python unittest + checker hiện có | Fence, JSON syntax và local inline links |

Nguồn: [Boot test dependencies](https://docs.spring.io/spring-boot/4.0/appendix/dependency-versions/coordinates.html), [Vitest package constraints](https://raw.githubusercontent.com/vitest-dev/vitest/v5.0.1/packages/vitest/package.json), [Playwright release](https://github.com/microsoft/playwright/releases/tag/v1.63.0).

Không lấy browser UI tests thay unit tests nghiệp vụ. Mọi acceptance vẫn theo [11](../quality/11_test_strategy.md); test tool chưa được cài trong repo.

Các phần chưa chọn exact version có chủ ý:

- **PLT-02:** OpenAPI/JSON Schema validator, generator/mock và Mermaid renderer. Chọn theo contract thực thi; không thêm Swagger runtime chỉ để có tài liệu.
- **SEC-01/PLT-04:** secret/dependency/container scanners và rule/database update policy. Bắt buộc trước release, không coi trì hoãn chọn tool là bỏ scan.
- **QA-03:** load runner và failure harness theo profile L1/L2/L3; không suy unit/E2E tool đáp ứng load test.
- **PLT-05:** log collector, alert routing, retention, sampling và manifests; kiểm tra resource budget trước cài.
- **PLT-01/04:** Maven Wrapper distribution/checksum, base-image digest, native CPU architecture, AMI và static web server. Version phần mềm ở bảng không phải container tag hoặc digest đã xác minh.
- Email/provider SDK chỉ chọn sau contract/quyền sandbox ở [15](15_integrations.md). VNPay/MoMo vẫn thuộc MVP; carrier, OTP/social login thuộc Phase 2.

## 7. Compatibility spike và phân công — O02

| Chủ trì / task | Thử nghiệm bắt buộc | Điều kiện đạt |
|---|---|---|
| TL + BE + DEVOPS / PLT-01 | Một service mẫu + Gateway + Nacos; resolve BOM; config import, discovery, auth, route, restart/reconnect | Build sạch; không tắt compatibility verifier; không override dependency để che lỗi; lưu effective POM/tree |
| BE / PLT-01, QA-01 | PostgreSQL 17.11 + driver/Flyway; fresh migration, upgrade schema, concurrent reservation/refund | Constraint/row lock đúng; runtime user không có DDL; migration user riêng |
| BE + DEVOPS / PLT-01/03 | Kafka KRaft, outbox/inbox, Redis auth/TTL và outage | Không mất durable work; duplicate không lặp side effect; cache down không đổi authority |
| FE / PLT-01 | Node/npm lockfile, typecheck/build/Vitest/Playwright; static serving, reload deep link, refresh cookie/CORS/CSRF | Clean install không force/legacy-peer-deps; tests pass; không đưa secret vào bundle |
| PO + FE / trước WEB-01 | Xác nhận SPA đáp ứng yêu cầu SEO/preview của MVP | Có quyết định; nếu không, sửa ADR-17 trước implementation storefront |
| DEVOPS / PLT-04 | Pin digest; K3s/Argo/ingress; rollout, schema-compatible rollback; ECR pull sau token expiry | Smoke pass; đo peak RAM cả rollout; không public control plane/DB |
| DEVOPS + BE / PLT-05 | Metrics/logs/trace xuyên HTTP/Kafka, restart, disk retention | Có query/dashboard/alert evidence; tài nguyên trong budget |
| TL + QA / trước release | License, CVE, dependency/image scan, restore và acceptance theo 11 | Reviewer ký; không phát hành chỉ vì docs CI xanh |

Thứ tự: core backend + frontend → contracts → vertical slice COD → staging → observability/recovery theo backlog. Không scaffold toàn bộ 9 service để chứng minh version.

Evidence phải ghi component/version/digest, OS/CPU, command, kết quả, commit/report, ngày, owner/reviewer và lỗi còn mở. Hiện tất cả runtime checks trong bảng là **chưa thực hiện**. Research chỉ giảm rủi ro lựa chọn, không đóng task PLT-01 hoặc gate G0/G2.

## 8. Khóa và nâng cấp để ít tốn công về sau

1. **Một nguồn thực thi:** backend BOM + Maven Wrapper; frontend exact direct versions + một `package-lock.json` ở workspace root; local/CI cài bằng `npm ci` sau khi có lockfile. Không commit đồng thời yarn.lock/pnpm-lock.yaml.
2. **Pin deployment:** image theo digest; ghi version, kiến trúc và checksum lúc tải. Không dùng `latest`, snapshot, RC/beta làm release identity. Action GitHub pin commit SHA.
3. **Nâng theo nhóm:** Boot–Cloud–Alibaba–Nacos; Node–npm–React–Vite–plugin–Router–Vitest; K3s bundle–Argo. Không tự động merge major upgrade.
4. **Lịch review:** security advisories hằng tuần và trước release; gom patch thường vào một PR review hằng tháng. Lỗ hổng nghiêm trọng ảnh hưởng thực tế xử lý ngay theo 13, không chờ lịch.
5. **Cùng major vẫn test:** dependency resolution → unit → integration/contract → E2E → staging smoke/restart. Có scan transitive/base image; BOM không bảo đảm không CVE.
6. **Major/schema upgrade:** ADR + release notes + backup/restore rehearsal; PostgreSQL major cần kế hoạch migration, không chỉ đổi image tag. Rollback app phải tương thích schema; không drop dữ liệu để quay lại.
7. **Theo dõi support:** lưu link chính sách và ngày kiểm tra trong release record; review trước EOL ít nhất 90 ngày. Không giả định mọi minor đều là LTS hoặc mọi giấy phép đều giống bản cũ.

Nguồn phiên bản thực thi sau PLT-01 là build/lock/manifests; 17 là giải thích lựa chọn, 08 là quyết định/ngoại lệ, [12](12_engineering_guide.md) là quy trình. Khi lệch phải ghi drift và đồng bộ trong cùng PR, không chọn tài liệu mới nhất làm bằng chứng đã triển khai.
