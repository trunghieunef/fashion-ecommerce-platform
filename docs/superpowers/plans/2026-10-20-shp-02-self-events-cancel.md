# SHP-02 SELF Events and Cancel Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** OPS SELF evidence events (state machine + COD collected/remitted) và internal cancel/tombstone cho shipping-service.

**Architecture:** Mở rộng shipping-service SHP-01: một TX cho mỗi lệnh (idempotency `begin/finish`, khóa hàng shipment,
history, outbox). Cancel dùng chung advisory lock theo `quote_id` với create. Không thêm service/bảng.

**Tech Stack:** Java 21, Spring Boot 4.0, PostgreSQL 17.11, Testcontainers.

**Spec:** [SHP-02 design](../specs/2026-10-20-shp-02-self-events-cancel-design.md)

## Global Constraints

- Permission `shipping.self_event` (OPS, đã seed ở user-service V003); cancel chỉ `order-service`.
- Lỗi: 409 `INVALID_TRANSITION`/`VERSION_CONFLICT`/`SHIPMENT_CANCELLED`/`SHIPMENT_HANDED_OVER`/`SHIPMENT_CONFLICT`,
  400 `INVALID_FIELDS`, 404 `SHIPMENT_NOT_FOUND`/`QUOTE_NOT_FOUND`.
- Không stage 10 file `.sh` đang M và `.gitattributes`. Máy ít RAM: test theo module, stack compose tắt khi verify.

## Review Focus

- Không trạng thái nào trước DELIVERING có `handed_over_at` (code + CHECK V004) — Task 1/2.
- COD: chỉ khi DELIVERED, đúng số, một lần; nộp không vượt đã thu — Task 2.
- Tombstone chặn create đến muộn (cả cùng body); cancel và create song song cùng quote — Task 3.
- Sau bàn giao cancel → 409, không đổi trạng thái — Task 3.

## Tasks

- [ ] **Task 1 — Schema/contract:** V004 CHECK `handed_over_at`; event schema `CodMovement`, registry 3 event, fixtures;
  OpenAPI self-events + cancel; contract tests. Verify: contract tests, Redocly lint.
- [ ] **Task 2 — Self events:** `SelfEventService` (parse strict theo `event_type`, bảng cạnh, version, idempotency, history
  `self:<id>`, outbox); endpoint admin `POST /{id}/self-events`; `ShipmentEvents.status/cod`. Test RED trước:
  `SelfEventIntegrationTest` (đường đủ, giao lại, hoàn, cạnh sai, version, replay, song song, COD, quyền, audit).
- [ ] **Task 3 — Cancel:** `ShipmentService.cancel` + endpoint internal; create trả 409 `SHIPMENT_CANCELLED`. Test RED trước:
  `ShipmentCancelIntegrationTest` (trước bàn giao, tombstone, create sau tombstone, song song, sau bàn giao, lặp, lệch snapshot).
- [ ] **Task 4 — Docs/verify:** 03/05/06/08/10/12, README, evidence; `./mvnw -pl services/shipping-service verify`,
  contract tests, docs check; review toàn nhánh bằng subagent và sửa Critical/Important; push `dev`, PR vào `main`.
