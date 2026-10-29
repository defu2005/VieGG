import { useCallback, useEffect, useState } from 'react';
import { api, newIdempotencyKey } from '../api/client';
import type { ExtensionCheckout, ExtensionOptions, Order } from '../api/types';
import { errorMessage, formatDateTime, formatVnd } from '../lib/format';
import { durationLabel } from '../lib/pricing';
import { Countdown } from './Countdown';
import { WalletBalanceLine } from './WalletBalanceLine';
import { nowMs } from '../lib/serverClock';

/** Shown as "sắp hết giờ" from this many minutes before the end. */
const SOON_MINUTES = 15;

/**
 * Extending a running rental from the order page: pick a duration, pay, done. No new booking, deposit,
 * owner approval or handover. Choices that would run into the next booking are shown disabled.
 */
export function ExtendRentalSection({ order, isRenter, onChanged }: { order: Order; isRenter: boolean; onChanged: () => void }) {
  const [data, setData] = useState<ExtensionOptions | null>(null);
  const [selected, setSelected] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const load = useCallback(() => {
    api<ExtensionOptions>(`/orders/${order.id}/extensions`)
      .then((d) => {
        setData(d);
        setSelected((cur) => {
          if (cur != null && d.options.some((o) => o.durationMinutes === cur && o.available)) return cur;
          return d.options.find((o) => o.available)?.durationMinutes ?? null;
        });
      })
      .catch((err) => setError(errorMessage(err)));
    // order.version: reload when the order itself changed (e.g. after an extension was paid).
  }, [order.id, order.version]);

  useEffect(() => {
    load();
  }, [load]);

  const run = async (fn: () => Promise<unknown>, success?: string) => {
    setError(null);
    setNotice(null);
    setBusy(true);
    try {
      await fn();
      if (success) setNotice(success);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
      load();
    }
  };

  if (!data) return null;
  const paid = data.history.filter((e) => e.status === 'PAID');
  const pending = data.pending;

  // Owner (or renter after the rental): only the record of what was extended.
  if (!isRenter || order.status !== 'IN_RENTAL') {
    if (paid.length === 0) return null;
    return (
      <section className="card stack">
        <h2>Gia hạn</h2>
        <ExtensionHistory items={paid} />
      </section>
    );
  }

  const minutesLeft = (new Date(order.scheduledEndAt).getTime() - nowMs()) / 60000;
  const soon = minutesLeft <= SOON_MINUTES;
  const choice = data.options.find((o) => o.durationMinutes === selected) ?? null;

  // Paid from the wallet right away. Not enough balance: the time stays reserved (pending) until the deadline.
  const onExtend = () =>
    run(async () => {
      if (!choice) return;
      await api<ExtensionCheckout>(`/orders/${order.id}/extensions`, {
        method: 'POST',
        body: { durationMinutes: choice.durationMinutes },
        idempotencyKey: newIdempotencyKey(),
      });
      onChanged();
    }, 'Gia hạn thành công, đã trừ tiền ví.');

  const onPayPending = () =>
    run(async () => {
      if (!pending) return;
      await api<ExtensionCheckout>(`/orders/${order.id}/extensions/${pending.id}/pay`, { method: 'POST' });
      onChanged();
    }, 'Gia hạn thành công, đã trừ tiền ví.');

  const onCancelPending = () =>
    run(async () => {
      if (!pending) return;
      await api(`/orders/${order.id}/extensions/${pending.id}/cancel`, { method: 'POST' });
    }, 'Đã hủy yêu cầu gia hạn.');

  return (
    <section className="card stack" aria-labelledby="extend-title">
      <div className="row spread">
        <h2 id="extend-title">Gia hạn thuê</h2>
        <span className={soon ? 'tag tag-hot' : 'muted'}>
          Còn <Countdown until={order.scheduledEndAt} onExpire={load} />
        </span>
      </div>

      {pending ? (
        <>
          <p className="banner" style={{ margin: 0 }}>
            Đang giữ thêm {durationLabel(pending.durationMinutes)} cho bạn (đến {formatDateTime(pending.newScheduledEndAt)}). Thanh toán trong{' '}
            <Countdown until={pending.paymentExpiresAt} onExpire={load} />, quá hạn phần giữ chỗ sẽ được trả lại.
          </p>
          <WalletBalanceLine amount={pending.rentAmount} refreshKey={error} />
          <div className="row">
            <button className="btn btn-primary" disabled={busy} onClick={onPayPending}>
              Thanh toán bằng ví · {formatVnd(pending.rentAmount)}
            </button>
            <button className="btn" disabled={busy} onClick={onCancelPending}>
              Hủy yêu cầu
            </button>
          </div>
        </>
      ) : !data.allowed ? (
        <p className="muted" style={{ margin: 0 }}>
          {data.blockedReason ?? 'Đơn này hiện không gia hạn được.'}
        </p>
      ) : (
        <>
          <p className="muted" style={{ margin: 0 }}>
            Chơi tiếp mà không cần đặt đơn mới: không đóng cọc lại, không chờ duyệt, giữ nguyên tài khoản đang dùng.
          </p>
          <div className="chips" role="group" aria-label="Chọn thời gian gia hạn">
            {data.options.map((o) => (
              <button
                type="button"
                key={o.durationMinutes}
                className={`chip ${selected === o.durationMinutes ? 'active' : ''}`}
                aria-pressed={selected === o.durationMinutes}
                disabled={!o.available}
                title={o.unavailableReason ?? undefined}
                onClick={() => setSelected(o.durationMinutes)}
              >
                +{durationLabel(o.durationMinutes)} · {formatVnd(o.price)}
              </button>
            ))}
          </div>
          {data.nextBookingAt && (
            <span className="field-hint">Account có lịch tiếp theo lúc {formatDateTime(data.nextBookingAt)}, chỉ gia hạn được đến trước giờ đó.</span>
          )}
          {data.options.every((o) => !o.available) ? (
            <p className="banner" style={{ margin: 0 }}>
              Không còn khung giờ trống để gia hạn{data.options[0]?.unavailableReason ? ` (${data.options[0].unavailableReason.toLowerCase()})` : ''}.
            </p>
          ) : (
            choice && (
              <div className="summary" aria-live="polite">
                <div className="line">
                  <span>Kết thúc mới</span>
                  <span>{formatDateTime(choice.newEndAt)}</span>
                </div>
                <div className="line total">
                  <span>Thanh toán thêm</span>
                  <span>{formatVnd(choice.price)}</span>
                </div>
              </div>
            )
          )}
          {choice?.available && <WalletBalanceLine amount={choice.price} refreshKey={order.version} />}
          <button className="btn btn-primary" disabled={busy || !choice?.available} onClick={onExtend}>
            {busy ? 'Đang gia hạn…' : choice ? `Gia hạn +${durationLabel(choice.durationMinutes)} · ${formatVnd(choice.price)}` : 'Gia hạn'}
          </button>
        </>
      )}

      {notice && <p className="success" style={{ margin: 0 }}>{notice}</p>}
      {error && <p className="error" style={{ margin: 0 }}>{error}</p>}
      {paid.length > 0 && <ExtensionHistory items={paid} />}
    </section>
  );
}

function ExtensionHistory({ items }: { items: ExtensionOptions['history'] }) {
  return (
    <ul className="muted" style={{ paddingLeft: '1.1rem', margin: 0 }}>
      {items.map((e) => (
        <li key={e.id}>
          +{durationLabel(e.durationMinutes)} · {formatVnd(e.rentAmount)} · đến {formatDateTime(e.newScheduledEndAt)}
        </li>
      ))}
    </ul>
  );
}
