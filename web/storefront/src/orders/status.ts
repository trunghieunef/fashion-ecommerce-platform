// TASK:WEB-03: customer words for order states (14 §4); no saga, lease or UNKNOWN wording reaches the visitor.
import type { Order, OrderStatus } from '../checkout/api';
import type { StringKey } from '../i18n';

const LABEL: Record<OrderStatus, StringKey> = {
  PENDING: 'osPending', WAITING_PAYMENT: 'osWaitingPayment', CONFIRMED: 'osConfirmed', PAID: 'osPaid', PACKING: 'osPacking',
  SHIPPING: 'osShipping', DELIVERED: 'osDelivered', CANCELLING: 'osCancelling', CANCELLED: 'osCancelled', REFUNDING: 'osRefunding',
  REFUNDED: 'osRefunded', RETURNING: 'osReturning', RETURNED: 'osReturned',
};
const CANCELLED: Record<string, StringKey> = {
  OUT_OF_STOCK: 'ocOutOfStock', RESERVE_FAILED: 'ocOutOfStock', EXPIRED: 'ocExpired', OPS_CANCELLED: 'ocShop', CUSTOMER_CANCELLED: 'ocCustomer',
};

/** Short label (order list, badge). */
export const statusLabel = (status: OrderStatus): StringKey => LABEL[status];

/** The sentence on the order page; a cancelled order says why in customer terms. */
export const statusText = (order: Order): StringKey =>
  order.status === 'CANCELLED' ? CANCELLED[order.reason_code ?? ''] ?? 'osCancelled' : LABEL[order.status];

export const MILESTONES: StringKey[] = ['msReceived', 'msConfirmed', 'msShipping', 'msDelivered'];
const STEP: Partial<Record<OrderStatus, number>> = { PENDING: 0, CONFIRMED: 0, PAID: 0, PACKING: 1, SHIPPING: 2, DELIVERED: 3 };
/** Index into MILESTONES, or null when the order left the normal path (cancelled, returned…). */
export const milestone = (status: OrderStatus): number | null => STEP[status] ?? null;
