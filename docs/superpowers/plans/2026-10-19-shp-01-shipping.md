# SHP-01 Shipping Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** shipping-service với SELF fee rules, estimate public, quote internal, shipment SELF create/read, OPS rules/shipments.

**Architecture:** Service mới theo mẫu cart-service (JdbcClient, Flyway, `Api` envelope, `ApiExceptionHandler`, no-store) +
outbox/relay như inventory (platform-messaging, registry copy vào classpath). Catalog quote qua service token để lấy
`weight_grams`. Mọi ghi trong một TX; không HTTP trong TX.

**Tech Stack:** Java 21, Spring Boot 4.0, PostgreSQL 17.11, Kafka (relay), Testcontainers.

**Spec:** [SHP-01 design](../specs/2026-10-19-shp-01-shipping-design.md)

## Global Constraints

- Port 8085, DB `shipping` (`shipping_migration`/`shipping_runtime`); route public `/api/v1/shipping/**`, admin `/admin/api/v1/shipping/**`.
- Internal callers: quotes/shipments chỉ `order-service`; catalog allowlist thêm `shipping-service`.
- Permissions: `shipping.read` (shipments), `shipping.rules` (rules). Lỗi: 422 `NO_SHIPPING_RULE`/`VARIANT_NOT_AVAILABLE`,
  409 `SHIPMENT_CONFLICT`/`QUOTE_MISMATCH`/`VERSION_CONFLICT`, 404 `QUOTE_NOT_FOUND`/`SHIPMENT_NOT_FOUND`, 503 `DEPENDENCY_UNAVAILABLE`.
- Không stage file `.sh` đang M của chủ dự án; file mới `infra/local/postgres-init/05-shipping-db.sh` được phép.

## Review Focus

- Hai create song song cùng order → một shipment, cả hai trả cùng shipment — Task 3.
- Quote tỉnh khác / COD khác đơn → 409 QUOTE_MISMATCH; dùng lại quote cho đơn khác → 409 — Task 3.
- Tier WEIGHT biên `weight == max_weight_grams` khớp tier đó — Task 2.
- Tràn `weight_grams × quantity` → 400, không 500 — Task 2.
- Internal 401/403 trước khi parse body — Task 2/3.

---

### Task 1: Khung service + schema + hạ tầng local
- [ ] pom/Dockerfile/application.yaml/beans/web; V001 (rules, quotes, shipments, history, idempotency, outbox, processed_events), V002 seed rules "(mẫu)".
- [ ] Root pom module; `05-shipping-db.sh`; compose service + env; local-up key `SHIPPING_SERVICE_*` + thêm vào `CATALOG_INTERNAL_CALLERS`; topic `shipping.events`; Gateway routes; catalog allowlist `shipping-service`.
- [ ] Test schema (constraints, runtime không DELETE).

### Task 2: Rules + fee (estimate, quote)
- [ ] `RuleConfig` validate/match, rule admin CRUD (version, audit `shipping_rule_changes`), `CatalogClient` weight, `FeeService`, routes estimate + internal quotes.
- [ ] Tests theo spec §7 Rule/Weight/Quote/Auth.

### Task 3: Shipments
- [ ] Internal create (idempotency + UNIQUE order_id/quote_id, PENDING→CREATED, history, outbox SHIPMENT_CREATED), by-order, admin list/detail.
- [ ] Event schema `shipping-events.schema.json` + registry + fixture; tests spec §7 Shipment.

### Task 4: Contract + docs + verify
- [ ] `contracts/openapi/shipping.yaml` + `shipping-internal.yaml` + test contract; README; 03/05/06/08/10/12; smoke estimate; evidence.
- [ ] Verify module (tắt compose), contract, docs, scan-secrets; review subagent; PR.
