package vn.fashion.inventory.reservation;

import java.time.Instant;
import java.util.UUID;

/** stock_reservations row; due = expires_at <= now() evaluated by the database in the same statement. */
public record ReservationRow(UUID id,UUID orderId,String orderNo,String requestHash,String status,Instant expiresAt,
    Instant committedAt,Instant releasedAt,String releaseReason,long version,boolean due) { }
