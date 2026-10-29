import { ApiError } from '../api/client';

const vnd = new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND', maximumFractionDigits: 0 });
const dateTime = new Intl.DateTimeFormat('vi-VN', {
  dateStyle: 'short',
  timeStyle: 'short',
  timeZone: 'Asia/Ho_Chi_Minh',
});

export function formatVnd(amount: number | null | undefined): string {
  return amount == null ? '—' : vnd.format(amount);
}

export function formatDateTime(iso: string | null | undefined): string {
  return iso ? dateTime.format(new Date(iso)) : '—';
}

/** Converts a value from <input type="datetime-local"> (local time) to an ISO instant for the API. */
export function localInputToIso(value: string): string {
  return new Date(value).toISOString();
}

/** Formats a Date as the value expected by <input type="datetime-local">, in local time. */
export function toLocalInput(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/** Message to show for any failed action. Server errors also quote the trace ID so support can find the log. */
export function errorMessage(err: unknown): string {
  if (err instanceof ApiError && err.status >= 500 && err.traceId) {
    return `${err.message} (Mã lỗi: ${err.traceId})`;
  }
  if (err instanceof Error) return err.message;
  return 'Có lỗi xảy ra, vui lòng thử lại.';
}
