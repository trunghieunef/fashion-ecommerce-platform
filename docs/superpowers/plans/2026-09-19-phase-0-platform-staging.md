# Phase 0 Platform and AWS Staging Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Sau S1-local, hoàn tất platform primitives, contract automation, application CI, security baseline, observability và deploy một service mẫu lên AWS staging để đạt G0.

**Architecture:** OpenAPI/JSON Schema là interface thực thi; mỗi service sở hữu DB/outbox/inbox và worker có lease/CAS. CI build artifact một lần và push ECR bằng GitHub OIDC; GitOps deploy digest lên K3s single-node qua Argo CD. AWS infrastructure uses CloudFormation and remains synthetic staging, not production/HA.

**Tech Stack:** R1 stack from `docs/engineering/17_tech_stack.md`; Kafka 4.1.2 KRaft, Redis 8.2.9, AWS EC2/K3s v1.35.8+k3s1, ECR, S3, CloudFormation, Kustomize, Argo CD 3.5.3, Prometheus 3.13.3 LTS, Grafana 13.2.2, Loki 3.7.8, Tempo 3.0.3.

**Spec:** `docs/delivery/10_backlog.md` PLT-02–05/SEC-01; `docs/engineering/16_aws_deployment.md`; `docs/operations/13_operations_security.md`; `docs/design/03_interfaces.md` and `06_service_flows.md` technical patterns.

## Global Constraints

- Start only after S1-local evidence is accepted and O01 records verified AWS plan/credit expiry, approved gross monthly budget, region, alert owner and permitted personal spend.
- Do not upgrade the AWS account plan, create Organizations/Control Tower, or provision resources until the reviewed CloudFormation change set and explicit cost approval exist.
- Staging target is one EC2/K3s node for a sample service; do not claim it runs full MVP, HA, production SLO, RPO or RTO.
- Build once, identify images by digest, promote through manifest PR; never deploy `latest`.
- GitHub PR jobs are read-only and have no deploy role. OIDC trust is restricted to repository, protected branch/environment and `aud=sts.amazonaws.com`.
- Keep DB/Kafka/Redis/Nacos/Argo CD/Kubernetes API and `/internal/**` private. Do not copy kubeconfig or long-lived AWS credentials into GitHub.
- PostgreSQL remains authority; outbox/inbox/lease mutations share the owner DB transaction and Kafka offsets are acknowledged only after commit.
- Redis 8.2 licensing and all dependency/image CVEs must have review evidence before G0.
- Commit steps require explicit user authorization; otherwise hand off the verified diff and proposed message.

## Review Focus

- Re-delivery after a crash between Kafka publish and marking outbox `SENT` must not repeat business effects; Task 2 pins event ID and inbox dedupe.
- An expired worker lease must reject the old worker's result even if it finishes successfully; Task 2 pins token CAS.
- Forked PRs or unprotected refs must be unable to assume AWS roles; Task 4 tests trust-policy rejection.
- A manifest must not expose `/internal`, data stores or control planes through ingress/security groups; Task 5 scans rendered resources and probes staging.
- Node restart and ECR credential expiry must recover the sample service without replacing a digest or losing durable data; Task 6 rehearses both.

---

## File Map

| Path | Responsibility |
|---|---|
| `contracts/openapi/*.yaml`, `contracts/events/*.schema.json` | API/event source of truth and examples |
| `services/platform-durability/` | Reusable source module for outbox, inbox, idempotency and leases; no domain entities |
| `tests/integration/platform/` | PostgreSQL/Kafka crash and duplicate proofs |
| `infra/aws/cloudformation/` | VPC, EC2, IAM, ECR, S3 and budget-alert infrastructure |
| `infra/environments/staging/` | Kustomize base/overlay, digest pins and private routes |
| `.github/workflows/application-ci.yml`, `publish-images.yml` | Read-only PR CI and protected-branch artifact publication |
| `infra/argocd/` | Scoped staging application/project; no public UI |
| `infra/observability/` | Resource-bounded metrics/log/trace setup and alerts |
| `docs/evidence/g0-template.md` | Cost, deploy, security, restart, rollback and reviewer evidence |

### Task 1: Make core contracts executable (`TASK:PLT-02`, `REQ:ORD-01`, `REQ:XCT-02`)

**Files:**
- Create: `contracts/openapi/common.yaml`, service files under `contracts/openapi/`
- Create: `contracts/events/event-envelope.schema.json`, per-event schemas under `contracts/events/`
- Create: `contracts/fixtures/invalid/`, `contracts/fixtures/valid/`
- Create: `scripts/validate-contracts.sh`, `tests/contracts/test_contract_examples.py`
- Modify: `docs/design/03_interfaces.md`, `docs/design/07_diagrams.md`

**Interfaces:**
- Consumes: canonical paths/envelopes/events in 03 and producer/consumer matrix in 04.
- Produces: `make`-free command `bash scripts/validate-contracts.sh`; JSON Schema envelope fields `event_id`, `event_type`, `event_version`, `aggregate_id`, `aggregate_version`, `aggregate_sequence`, `occurred_at`, `trace_id`, `payload`.

- [ ] **Step 1: Write failing fixture tests**

```python
def test_event_example_has_stable_identity_and_sequence():
    event = json.loads(Path("contracts/fixtures/valid/order-created.json").read_text())
    assert UUID(event["event_id"])
    assert event["aggregate_version"] >= 1
    assert event["aggregate_sequence"] >= 1
    assert event["event_version"] == 1

def test_invalid_money_fixture_is_rejected():
    result = run_validator("contracts/fixtures/invalid/order-float-money.json")
    assert result.returncode != 0
```

- [ ] **Step 2: Run and verify failure**

Run: `python3 -B -m unittest tests.contracts.test_contract_examples -v`

Expected: FAIL because executable schemas/fixtures are absent.

- [ ] **Step 3: Add the exact event envelope schema**

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://fashion.local/contracts/event-envelope.schema.json",
  "type": "object",
  "required": ["event_id", "event_type", "event_version", "aggregate_id", "aggregate_version", "aggregate_sequence", "occurred_at", "trace_id", "payload"],
  "properties": {
    "event_id": {"type": "string", "format": "uuid"},
    "event_type": {"type": "string", "minLength": 1},
    "event_version": {"type": "integer", "minimum": 1},
    "aggregate_id": {"type": "string", "minLength": 1},
    "aggregate_version": {"type": "integer", "minimum": 1},
    "aggregate_sequence": {"type": "integer", "minimum": 1},
    "occurred_at": {"type": "string", "format": "date-time"},
    "trace_id": {"type": "string", "minLength": 1},
    "payload": {"type": "object"}
  },
  "additionalProperties": false
}
```

Encode VND as JSON integer, timestamps UTC date-time, UUIDs strings and snake_case. Add only endpoints/events needed by current and next plan; schema titles may cover future operations, but examples must not claim implementation.

- [ ] **Step 4: Validate positive and negative fixtures**

Run: `bash scripts/validate-contracts.sh && python3 -B -m unittest tests.contracts.test_contract_examples -v`

Expected: PASS; every valid fixture is accepted and each invalid fixture fails for its named reason.

- [ ] **Step 5: Review consumers and checkpoint**

Record producer/consumer reviewers in the contract README and update 03/07 only where executable details differ. If commit permission exists:

```bash
git add contracts scripts/validate-contracts.sh tests/contracts docs/design/03_interfaces.md docs/design/07_diagrams.md
git commit -m "feat(PLT-02): make core contracts executable"
```

### Task 2: Implement durable primitives (`TASK:PLT-03`, `REQ:ORD-02`, `REQ:PAY-07`)

**Files:**
- Create: `services/platform-durability/pom.xml`
- Create: `services/platform-durability/src/main/java/vn/fashion/platform/outbox/OutboxRepository.java`
- Create: `services/platform-durability/src/main/java/vn/fashion/platform/inbox/InboxGuard.java`
- Create: `services/platform-durability/src/main/java/vn/fashion/platform/idempotency/IdempotencyStore.java`
- Create: `services/platform-durability/src/main/java/vn/fashion/platform/work/LeaseRepository.java`
- Create: `services/platform-durability/src/main/resources/db/platform/V001__durability.sql`
- Create: `tests/integration/platform/src/test/java/vn/fashion/platform/DurabilityIntegrationTest.java`
- Modify: `pom.xml`, `docs/design/05_database_design.md`, `docs/design/06_service_flows.md`

**Interfaces:**
- Consumes: service-owned `JdbcClient` and caller transaction; no cross-service database.
- Produces: `append(OutboxEvent)`, `claimBatch(workerId, limit, leaseUntil)`, `markSent(eventId, leaseToken)`, `applyOnce(eventId, consumer, effect)`, `begin(scope,key,requestHash)`, `finish(scope,key,response)`, `completeWork(workId,leaseToken,result)`.

- [ ] **Step 1: Write crash/duplicate/lease tests**

```java
@Test void duplicateInboxEventCommitsOneEffect() {
  applyTwiceConcurrently(EVENT_ID);
  assertThat(jdbc.sql("select count(*) from test_effects where event_id=:id").param("id", EVENT_ID).query(Integer.class).single()).isEqualTo(1);
}

@Test void staleLeaseCannotCompleteWork() {
  var old = leases.claim(WORK_ID, "w1", clock.instant().plusSeconds(5));
  clock.advance(Duration.ofSeconds(6));
  var current = leases.claim(WORK_ID, "w2", clock.instant().plusSeconds(5));
  assertThat(leases.complete(WORK_ID, old.token(), "old")).isFalse();
  assertThat(leases.complete(WORK_ID, current.token(), "new")).isTrue();
}

@Test void republishUsesSameEventIdAndSequence() {
  relay.publishThenCrash(EVENT_ID);
  relay.retry();
  assertThat(broker.records(EVENT_ID)).hasSize(2).allMatch(r -> r.sequence() == 7);
}
```

- [ ] **Step 2: Run and verify failure**

Run: `./mvnw -pl tests/integration/platform -Dtest=DurabilityIntegrationTest test`

Expected: FAIL because module/schema/repositories are absent.

- [ ] **Step 3: Add schema constraints that carry correctness**

```sql
CREATE TABLE outbox_events (
  event_id uuid PRIMARY KEY, aggregate_id varchar(128) NOT NULL,
  aggregate_version bigint NOT NULL, aggregate_sequence bigint NOT NULL,
  event_type varchar(128) NOT NULL, payload jsonb NOT NULL,
  status varchar(16) NOT NULL CHECK (status IN ('PENDING','LEASED','SENT')),
  available_at timestamptz NOT NULL, lease_token uuid, lease_until timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(), sent_at timestamptz,
  UNIQUE (aggregate_id, aggregate_sequence)
);
CREATE TABLE processed_events (
  consumer varchar(128) NOT NULL, event_id uuid NOT NULL,
  processed_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY (consumer,event_id)
);
```

Add idempotency request hash/response/status and background work lease token/version tables exactly as 05 requires. Use `FOR UPDATE SKIP LOCKED` to claim bounded batches and `WHERE lease_token=:token` for completion.

- [ ] **Step 4: Implement transaction templates without starting nested remote work**

```java
public <T> T applyOnce(UUID eventId, String consumer, Supplier<T> localEffect) {
  return transactions.execute(status -> {
    int inserted = jdbc.sql("insert into processed_events(consumer,event_id) values (:c,:e) on conflict do nothing")
        .param("c", consumer).param("e", eventId).update();
    return inserted == 0 ? null : localEffect.get();
  });
}
```

The module contains no shared domain entity and performs no HTTP/provider call.

- [ ] **Step 5: Run PostgreSQL/Kafka failure proofs**

Run: `./mvnw -pl services/platform-durability,tests/integration/platform test`

Expected: PASS for duplicate, hash mismatch 409 mapping, crash-after-publish, same-version event ordering and stale lease CAS.

- [ ] **Step 6: Synchronize docs and checkpoint**

Update 05/06 with actual columns/signatures while preserving service ownership. If commit permission exists:

```bash
git add pom.xml services/platform-durability tests/integration/platform docs/design/05_database_design.md docs/design/06_service_flows.md
git commit -m "feat(PLT-03): add durable delivery primitives"
```

### Task 3: Complete the security baseline (`TASK:SEC-01`, `REQ:USR-07`, `REQ:USR-08`, `REQ:XCT-03`)

**Files:**
- Create: `services/platform-security/` focused JWT/service identity module
- Create: `tests/integration/security/ServiceIdentityIntegrationTest.java`
- Create: `docs/security/threat-model.md`, `docs/security/data-inventory.md`
- Modify: `services/gateway/`, `docs/operations/13_operations_security.md`, `docs/design/08_decisions.md`

**Interfaces:**
- Consumes: issuer, audience, JWKS and caller allowlist via injected config.
- Produces: `ActorContext(actorId, actorType, authVersion, permissions)` and `ServiceCaller(name,audience)`; denied requests make no mutation.

- [ ] **Step 1: Write authorization/redaction tests**

```java
@Test void tokenForWrongAudienceIsRejectedWithoutMutation() {
  callInternal(tokenWithAudience("other-service")).expectStatus().isForbidden();
  assertThat(effectCount()).isZero();
}

@Test void logsNeverContainTokensOrEmail() {
  triggerDeniedRequest("person@example.test", "Bearer synthetic-secret");
  assertThat(capturedLogs()).doesNotContain("person@example.test", "synthetic-secret");
}
```

- [ ] **Step 2: Run and verify failure**

Run: `./mvnw -pl tests/integration/security test`

Expected: FAIL until service identity, permission and redaction code exists.

- [ ] **Step 3: Implement deny-by-default identity conversion**

```java
public record ActorContext(UUID actorId, String actorType, long authVersion, Set<String> permissions) {
  public boolean allows(String permission) { return permissions.contains(permission); }
}
```

Gateway authenticates external tokens; each service revalidates audience/issuer and checks ownership/permission. Service tokens use a different audience and caller allowlist. CSRF applies to cookie-authenticated mutations; CORS is an explicit allowlist.

- [ ] **Step 4: Run U03/U24 security subset and scans**

Run: `./mvnw -pl services/platform-security,tests/integration/security test && bash scripts/scan-secrets.sh`

Expected: spoofed header, wrong audience, revoked auth version, cross-origin mutation and PII-log fixtures all fail closed.

- [ ] **Step 5: Resolve governance evidence and checkpoint**

Assign owner/date to O06 without inventing legal retention outcomes; unresolved retention remains an explicit G2 gate. If commit permission exists:

```bash
git add services/platform-security tests/integration/security docs/security docs/operations/13_operations_security.md docs/design/08_decisions.md scripts/scan-secrets.sh
git commit -m "feat(SEC-01): establish service identity and data controls"
```

### Task 4: Build immutable artifacts with protected AWS OIDC (`TASK:PLT-04.A`, `TASK:PLT-04.D`)

**Files:**
- Create: `services/catalog-service/Dockerfile`, `services/gateway/Dockerfile`, `web/storefront/Dockerfile`
- Create: `.github/workflows/publish-images.yml`
- Create: `infra/aws/cloudformation/identity-and-ecr.yaml`
- Create: `infra/aws/cloudformation/parameters/staging.example.json`
- Create: `scripts/render-build-policy.sh`, `scripts/test-oidc-policy.sh`, `docs/evidence/aws-cost-template.md`
- Modify: `docs/engineering/16_aws_deployment.md`

**Interfaces:**
- Consumes: explicit account ID, region, repo/ref/environment, approved budget and reviewed base-image digests.
- Produces: ECR image digest per commit; build role can push only named repositories and cannot mutate EC2/IAM/DB.

- [ ] **Step 1: Add failing image and IAM policy tests**

```bash
docker build --pull=false -f services/catalog-service/Dockerfile -t catalog:test .
test "$(docker image inspect catalog:test --format '{{.Config.User}}')" = "10001:10001"
bash scripts/render-build-policy.sh > /tmp/fashion-build-role-policy.json
aws iam simulate-custom-policy --policy-input-list file:///tmp/fashion-build-role-policy.json \
  --action-names ec2:TerminateInstances iam:CreateRole ecr:PutImage
```

Expected before implementation: build/policy files missing. Expected after implementation: EC2/IAM decisions `implicitDeny`; ECR is allowed only for the exact repository ARN.

- [ ] **Step 2: Pin non-root multi-stage images**

```dockerfile
ARG BUILD_IMAGE
ARG RUNTIME_IMAGE
FROM ${BUILD_IMAGE} AS build
WORKDIR /src
COPY . .
RUN ./mvnw -pl services/catalog-service -am -DskipTests package
FROM ${RUNTIME_IMAGE}
USER 10001:10001
COPY --from=build /src/services/catalog-service/target/catalog-service.jar /app.jar
ENTRYPOINT ["java","-jar","/app.jar"]
```

Build with `--build-arg BUILD_IMAGE="$TEMURIN_21_JDK_DIGEST" --build-arg RUNTIME_IMAGE="$TEMURIN_21_JRE_DIGEST"`; the CI environment values must each be a full registry reference ending in `@sha256:<64 lowercase hex characters>`. The workflow rejects absent or non-matching values before invoking Docker and records registry/tag/platform/date in evidence.

- [ ] **Step 3: Implement protected publication**

Workflow runs only after protected-main tests, uses GitHub OIDC, tags commit SHA, captures digest and scan report, and never runs AWS credentials on fork PRs. Trust policy matches exact repository and protected environment subject.

- [ ] **Step 4: Test both allowed and denied subjects**

Run: `bash scripts/test-oidc-policy.sh`

Expected: protected main/environment subject allowed; fork, tag, pull-request and other repository subjects denied.

- [ ] **Step 5: Review cost/credit input and checkpoint**

Fill gross estimate for 160h and 730h profiles in `docs/evidence/aws-cost-template.md`; include EC2, T3 CPU mode, 40GiB gp3, IPv4, ECR, S3/snapshot, logs, DNS/egress and tax. Proceed to Task 5 only after explicit approval. If commit permission exists:

```bash
git add services/*/Dockerfile web/storefront/Dockerfile .github/workflows/publish-images.yml infra/aws scripts/render-build-policy.sh scripts/test-oidc-policy.sh docs/evidence/aws-cost-template.md docs/engineering/16_aws_deployment.md
git commit -m "build(PLT-04): secure image publication with AWS OIDC"
```

### Task 5: Provision and deploy the scoped staging platform (`TASK:PLT-04.C`, `TASK:PLT-04.E`)

**Files:**
- Create: `infra/aws/cloudformation/staging.yaml`
- Create: `infra/environments/base/`, `infra/environments/staging/`
- Create: `infra/argocd/staging-project.yaml`, `infra/argocd/staging-application.yaml`
- Create: `scripts/render-staging.sh`, `scripts/smoke-staging.sh`
- Test: `tests/infrastructure/test_staging_policy.py`

**Interfaces:**
- Consumes: approved change set, pinned AMI/K3s installer checksum/image digests, domain/TLS and secret references.
- Produces: one private-by-default K3s node, HTTPS public Gateway/storefront only, scoped Argo CD reconciliation and sample service readiness.

- [ ] **Step 1: Write failing rendered-manifest policy tests**

```python
def test_only_gateway_and_storefront_have_ingress(rendered):
    hosts = ingress_services(rendered)
    assert hosts == {"gateway", "storefront"}

def test_no_latest_or_plaintext_secret(rendered):
    assert ":latest" not in rendered
    assert "kind: Secret\n" not in rendered
    assert "/internal" not in public_ingress_paths(rendered)
```

- [ ] **Step 2: Run and verify failure**

Run: `python3 -B -m unittest tests.infrastructure.test_staging_policy -v`

Expected: FAIL because templates/manifests do not exist.

- [ ] **Step 3: Build CloudFormation and inspect without executing**

Template creates only reviewed VPC/public subnet/IGW, one EC2, encrypted gp3, least-privilege instance profile, named ECR/S3/log/budget resources and SG ingress 443 plus approved management path. Data resources use reviewed retention policies. Run:

```bash
aws cloudformation validate-template --template-body file://infra/aws/cloudformation/staging.yaml
aws cloudformation create-change-set --change-set-type CREATE --stack-name fashion-staging --change-set-name reviewed-g0 --template-body file://infra/aws/cloudformation/staging.yaml --parameters file://approved-staging-parameters.json
aws cloudformation describe-change-set --stack-name fashion-staging --change-set-name reviewed-g0
```

Expected: validation succeeds and reviewer sees exact additions; do not execute the change set until separate explicit approval.

- [ ] **Step 4: Render digest-pinned manifests**

Run: `bash scripts/render-staging.sh > /tmp/fashion-staging-rendered.yaml && python3 -B -m unittest tests.infrastructure.test_staging_policy -v`

Expected: PASS; every workload has requests/limits, readiness/liveness, namespace/service account and immutable digest; DB credentials differ by service.

- [ ] **Step 5: Execute only after approval and run network probes**

After approval, execute the exact reviewed change set, install checksum-pinned K3s/Argo CD, apply the scoped project/application and run `bash scripts/smoke-staging.sh`. Expected: HTTPS smoke passes; Internet probes to 22/6443/5432/6379/9092/8848/Argo UI and `/internal/**` fail.

- [ ] **Step 6: Checkpoint**

Record stack ID, account alias, region, resource tags, digest, manifest commit and reviewer without secrets. If commit permission exists:

```bash
git add infra/aws infra/environments infra/argocd scripts/render-staging.sh scripts/smoke-staging.sh tests/infrastructure
git commit -m "feat(PLT-04): deploy scoped K3s staging foundation"
```

### Task 6: Add observability, restart and rollback proof (`TASK:PLT-05`, `TASK:PLT-04.F`, `REQ:NFR-01`, `REQ:NFR-06`)

**Files:**
- Create: `infra/observability/`, `docs/evidence/g0-template.md`
- Create: `tests/integration/observability/CorrelationTest.java`
- Create: `scripts/rehearse-staging-recovery.sh`
- Modify: `docs/operations/13_operations_security.md`, `docs/engineering/16_aws_deployment.md`, `docs/delivery/09_delivery_plan.md`

**Interfaces:**
- Consumes: HTTP `traceparent`, event `trace_id`, JSON logs with redaction, previous/next image digests.
- Produces: RED metrics, oldest outbox gauge, one cross-HTTP/Kafka trace, bounded log retention, alerts and repeatable app rollback.

- [ ] **Step 1: Write failing correlation/redaction tests**

```java
@Test void traceIdCrossesHttpAndKafkaWithoutPii() {
  var trace = callCatalogAndPublishSyntheticEvent();
  assertThat(trace.spans()).extracting(Span::traceId).containsOnly(trace.traceId());
  assertThat(trace.logs()).doesNotContain("person@example.test", "Bearer ");
}
```

- [ ] **Step 2: Run and verify failure**

Run: `./mvnw -pl tests/integration/observability test`

Expected: FAIL until instrumentation and redaction are wired.

- [ ] **Step 3: Add resource-bounded telemetry and alerts**

Expose request rate/error/duration, DB pool wait, Kafka lag, oldest outbox, JVM and disk. Configure 30-day-or-less staging retention within measured disk budget. Alert thresholds start from 13 and include links to RB-02/RB-03.

- [ ] **Step 4: Rehearse failure and rollback**

`scripts/rehearse-staging-recovery.sh` must: capture digest/state, reboot node, wait for K3s/dependencies, verify ECR pull after credential refresh, run smoke, merge a manifest rollback to the previously tested digest through the approved GitOps path, and rerun smoke. It must not terminate EC2, delete a stack or mutate retained storage.

- [ ] **Step 5: Run G0 gate and record actual resource/cost data**

Run: `bash scripts/rehearse-staging-recovery.sh && bash scripts/smoke-staging.sh`

Expected: PASS with peak CPU/RAM/disk, rollout overlap, downtime, gross cost-to-date and alert-delivery evidence recorded. A restore drill is not claimed here; OPS-01 owns it before G2.

- [ ] **Step 6: Synchronize status and checkpoint**

Update 09/13/16 with actual evidence; G0 may be accepted only by named TL reviewer. If commit permission exists:

```bash
git add infra/observability tests/integration/observability scripts/rehearse-staging-recovery.sh docs
git commit -m "test(G0): prove staging observability and rollback"
```

## G0 Exit Gate

- PLT-02–05 and SEC-01 evidence is reviewed; contracts/primitives are exercised, not documentation-only.
- AWS cost, account/region, resource list and permissions match explicit approval.
- One sample service deploys by digest through scoped GitOps and survives restart/credential refresh.
- Public/internal network probes, spoof/revocation and PII redaction tests pass.
- Cross-service telemetry and alerts work within measured node budget.
- No full-MVP, HA, production or restore claim is made.
