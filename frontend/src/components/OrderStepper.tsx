import type { OrderStatus } from '../api/types';

const STEPS = ['Đặt đơn', 'Thanh toán', 'Chủ account duyệt', 'Đang thuê', 'Hoàn tất'];

/** Index of the step currently in progress, or null for a terminal failure state. */
function activeStep(status: OrderStatus): number | null {
  switch (status) {
    case 'PENDING_PAYMENT':
      return 1;
    case 'AWAITING_OWNER_APPROVAL':
      return 2;
    case 'CONFIRMED':
    case 'IN_RENTAL':
      return 3;
    case 'ENDED':
      return STEPS.length;
    default:
      return null;
  }
}

const FAILED_TEXT: Partial<Record<OrderStatus, string>> = {
  CANCELLED: 'Đơn đã bị hủy.',
  EXPIRED: 'Đơn đã hết hạn thanh toán.',
  REJECTED: 'Chủ account đã từ chối hoặc không duyệt đơn kịp.',
};

export function OrderStepper({ status }: { status: OrderStatus }) {
  const active = activeStep(status);
  if (active === null) return <p className="error">{FAILED_TEXT[status] ?? status}</p>;
  return (
    <ol className="steps" aria-label="Tiến trình đơn">
      {STEPS.map((label, i) => (
        <li key={label} className={i < active ? 'done' : i === active ? 'active' : ''} aria-current={i === active ? 'step' : undefined}>
          {label}
        </li>
      ))}
    </ol>
  );
}
