import { useCallback, useEffect, useState } from 'react';
import { Link, useLocation, useParams } from 'react-router-dom';
import { api, newIdempotencyKey } from '../api/client';
import type { Credentials, GameView, OrderDetail, PayResponse } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { Countdown } from '../components/Countdown';
import { DisputesSection, orderRisks } from '../components/DisputesSection';
import { ExtendRentalSection } from '../components/ExtendRentalSection';
import { OrderStepper } from '../components/OrderStepper';
import { OrderSupportSection } from '../components/OrderSupportSection';
import { StatusBadge } from '../components/StatusBadge';
import { UserRef } from '../components/UserRef';
import { USER_LOOKUP_PERMISSIONS } from '../lib/userDirectory';
import { WalletBalanceLine } from '../components/WalletBalanceLine';
import { errorMessage, formatDateTime, formatVnd } from '../lib/format';
import { CANCEL_REASON, DEPOSIT_STATUS, labelOf } from '../lib/labels';
import { durationLabel } from '../lib/pricing';

/** "?duration=60" so the listing page opens with the same package as this order. */
function rentAgainQuery(snapshot?: Record<string, unknown>): string {
  const d = snapshot?.durationMinutes;
  return typeof d === 'number' ? `?duration=${d}` : '';
}

export function OrderDetailPage() {
  const { id = '' } = useParams();
  const { me, can } = useAuth();

  const [data, setData] = useState<OrderDetail | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  // Set when we arrive from "Đặt và thanh toán" and paying from the wallet failed (usually not enough balance).
  const location = useLocation();
  const [actionError, setActionError] = useState<string | null>(
    () => (location.state as { payError?: string | null } | null)?.payError ?? null,
  );
  const [busy, setBusy] = useState(false);
  const [credentials, setCredentials] = useState<Credentials | null>(null);
  const [otpNote, setOtpNote] = useState('');
  const [notice, setNotice] = useState<string | null>(null);

  const [handoverMode, setHandoverMode] = useState<string | null>(null);

  const load = useCallback(() => {
    api<OrderDetail>(`/orders/${id}`)
      .then(setData)
      .catch((err) => setLoadError(errorMessage(err)));
  }, [id]);

  useEffect(() => {
    load();
  }, [load]);

  // OTP actions only apply to games whose handover needs the owner's OTP.
  const gameId = data?.order.gameId;
  useEffect(() => {
    if (!gameId) return;
    api<GameView>(`/games/${gameId}`)
      .then((g) => setHandoverMode(g.handoverMode))
      .catch((err) => {
        setHandoverMode(null);
        setActionError(`Không tải được thông tin game: ${errorMessage(err)}`);
      });
  }, [gameId]);

  /** Runs one order action, shows its error or success message, then reloads the order. */
  const run = async (fn: () => Promise<unknown>, successMessage?: string) => {
    setActionError(null);
    setNotice(null);
    setBusy(true);
    try {
      await fn();
      if (successMessage) setNotice(successMessage);
      load();
    } catch (err) {
      setActionError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  };

  const onPay = () =>
    run(
      () => api<PayResponse>(`/orders/${id}/pay`, { method: 'POST', idempotencyKey: newIdempotencyKey() }),
      'Đã thanh toán bằng số dư ví.',
    );

  const onCancel = () => {
    if (!window.confirm('Hủy đơn này? Phí hủy được tính theo chính sách.')) return;
    run(() => api(`/orders/${id}/cancel`, { method: 'POST' }), 'Đã hủy đơn.');
  };

  const onApprove = () => run(() => api(`/orders/${id}/approve`, { method: 'POST' }), 'Đã duyệt đơn.');
  const onReject = () => {
    if (!window.confirm('Từ chối đơn này?')) return;
    run(() => api(`/orders/${id}/reject`, { method: 'POST' }), 'Đã từ chối đơn.');
  };

  const onConfirmHandover = () =>
    run(() => api(`/orders/${id}/handover/confirm`, { method: 'POST' }), 'Đã xác nhận nhận account.');

  const onViewCredentials = () =>
    run(async () => {
      setCredentials(await api<Credentials>(`/orders/${id}/credentials`));
    });

  const onRequestOtp = () => run(() => api(`/orders/${id}/otp-request`, { method: 'POST' }), 'Đã gửi yêu cầu OTP.');

  const onSendOtpNote = () =>
    run(async () => {
      await api(`/orders/${id}/otp-note`, { method: 'POST', body: { note: otpNote } });
      setOtpNote('');
    }, 'Đã gửi mã OTP cho người thuê.');

  if (loadError) return <p className="error">{loadError}</p>;
  if (!data) return <p className="muted">Đang tải…</p>;

  const { order: o, deposit, refunds } = data;
  const isRenter = me?.id === o.renterId;
  const isOwner = me?.id === o.ownerId;
  const canHandover = isRenter && o.status === 'CONFIRMED' && !o.handoverConfirmedAt;
  const canViewCredentials = isRenter && (o.status === 'CONFIRMED' || o.status === 'IN_RENTAL');
  const canCancel = (isRenter || isOwner) && (o.status === 'PENDING_PAYMENT' || o.status === 'AWAITING_OWNER_APPROVAL' || o.status === 'CONFIRMED');
  const canOtp = handoverMode === 'OWNER_ASSISTED_OTP' && (o.status === 'CONFIRMED' || o.status === 'IN_RENTAL');

  return (
    <div className="stack">
      <p>
        <Link to="/orders">← Đơn của tôi</Link>
      </p>

      <section className="card">
        <div className="row spread">
          <h1>Đơn {o.id.slice(0, 8)}</h1>
          <StatusBadge status={o.status} />
        </div>
        <OrderStepper status={o.status} />
        {o.status === 'PENDING_PAYMENT' && o.paymentExpiresAt && (
          <p className="banner" style={{ margin: '0.75rem 0' }}>
            Thanh toán trong <Countdown until={o.paymentExpiresAt} onExpire={load} /> để giữ chỗ, quá hạn đơn sẽ tự hủy.
          </p>
        )}
        {o.status === 'AWAITING_OWNER_APPROVAL' && o.approvalExpiresAt && (
          <p className="banner" style={{ margin: '0.75rem 0' }}>
            Chủ account cần duyệt trong <Countdown until={o.approvalExpiresAt} onExpire={load} />. Không duyệt kịp, bạn được hoàn tiền về ví.
          </p>
        )}
        <dl className="kv">
          <dt>Bắt đầu</dt>
          <dd>{formatDateTime(o.startAt)}</dd>
          <dt>Kết thúc dự kiến</dt>
          <dd>{formatDateTime(o.scheduledEndAt)}</dd>
          {o.extendedMinutes > 0 && (
            <>
              <dt>Đã gia hạn</dt>
              <dd>
                +{durationLabel(o.extendedMinutes)} ({formatVnd(o.extensionRentAmount)})
              </dd>
            </>
          )}
          <dt>Tiền thuê</dt>
          <dd>{formatVnd(o.rentAmount)}</dd>
          <dt>Tiền cọc</dt>
          <dd>{formatVnd(o.depositAmount)}</dd>
          <dt>{o.extendedMinutes > 0 ? 'Tổng đã thanh toán' : 'Tổng thanh toán'}</dt>
          <dd>{formatVnd(o.amountToPay)}</dd>
          {o.paymentExpiresAt && o.status === 'PENDING_PAYMENT' && (
            <>
              <dt>Hạn thanh toán</dt>
              <dd>{formatDateTime(o.paymentExpiresAt)}</dd>
            </>
          )}
          {o.approvalExpiresAt && o.status === 'AWAITING_OWNER_APPROVAL' && (
            <>
              <dt>Hạn chủ account duyệt</dt>
              <dd>{formatDateTime(o.approvalExpiresAt)}</dd>
            </>
          )}
          {can(...USER_LOOKUP_PERMISSIONS) && (
            <>
              <dt>Người thuê</dt>
              <dd>
                <UserRef id={o.renterId} />
              </dd>
              <dt>Chủ account</dt>
              <dd>
                <UserRef id={o.ownerId} />
              </dd>
            </>
          )}
          {deposit && (
            <>
              <dt>Tiền cọc</dt>
              <dd>
                <StatusBadge status={deposit.status} dict={DEPOSIT_STATUS} />
              </dd>
            </>
          )}
          {o.cancelReason && (
            <>
              <dt>Lý do hủy</dt>
              <dd>{labelOf(CANCEL_REASON, o.cancelReason)}</dd>
            </>
          )}
        </dl>
        {refunds.length > 0 && (
          <div className="muted">
            <p style={{ margin: '0.5rem 0 0.25rem' }}>Hoàn tiền về ví người thuê:</p>
            <ul style={{ margin: 0, paddingLeft: '1.1rem' }}>
              {refunds.map((r) => (
                <li key={r.id}>
                  {r.kind === 'DEPOSIT' ? 'Tiền cọc' : 'Tiền thuê'} · {formatVnd(r.amount)} ·{' '}
                  {r.status === 'SUCCEEDED' ? 'đã vào ví' : 'đang xử lý'}
                </li>
              ))}
            </ul>
            {isRenter && <Link to="/wallet">Xem ví của tôi</Link>}
          </div>
        )}
      </section>

      {(notice || actionError) && (
        <section className="card">
          {notice && <p className="success">{notice}</p>}
          {actionError && <p className="error">{actionError}</p>}
        </section>
      )}

      {(o.status === 'IN_RENTAL' || o.extendedMinutes > 0) && (isRenter || isOwner) && (
        <div id="extend"><ExtendRentalSection order={o} isRenter={isRenter} onChanged={load} /></div>
      )}

      {isRenter && ['ENDED', 'CANCELLED', 'EXPIRED', 'REJECTED'].includes(o.status) && (
        <section className="card row spread">
          <span className="muted">Muốn thuê lại account này? Gói thuê cũ được chọn sẵn.</span>
          <Link className="btn btn-primary" to={`/listings/${o.listingId}${rentAgainQuery(o.policySnapshot)}`}>
            Thuê lại
          </Link>
        </section>
      )}

      {isRenter && o.status === 'PENDING_PAYMENT' && (
        <section className="card stack">
          <h2>Thanh toán</h2>
          <WalletBalanceLine amount={o.amountToPay} refreshKey={data} />
          <div>
            <button className="btn btn-primary" disabled={busy} onClick={onPay}>
              Thanh toán bằng ví · {formatVnd(o.amountToPay)}
            </button>
          </div>
        </section>
      )}

      {isOwner && o.status === 'AWAITING_OWNER_APPROVAL' && (
        <section id="approve" className="card stack">
          <h2>Duyệt đơn</h2>
          <p className="muted">Người thuê đã thanh toán. Duyệt để xác nhận đơn, hoặc từ chối để hoàn tiền.</p>
          <div className="row">
            <button className="btn btn-primary" disabled={busy} onClick={onApprove}>
              Duyệt đơn
            </button>
            <button className="btn btn-danger" disabled={busy} onClick={onReject}>
              Từ chối
            </button>
          </div>
        </section>
      )}

      {canHandover && (
        <section className="card stack">
          <h2>Bàn giao account</h2>
          <p className="muted">Sau khi đã kiểm tra và đăng nhập được, hãy xác nhận đã nhận account.</p>
          <button className="btn btn-primary" disabled={busy} onClick={onConfirmHandover}>
            Xác nhận đã nhận account
          </button>
        </section>
      )}

      {canViewCredentials && (
        <section id="credentials" className="card stack">
          <h2>Thông tin đăng nhập</h2>
          <p className="muted">Chỉ xem được trong khung giờ thuê. Mỗi lần xem đều được ghi nhật ký.</p>
          {!credentials ? (
            <button className="btn" disabled={busy} onClick={onViewCredentials}>
              Xem thông tin đăng nhập
            </button>
          ) : (
            <dl className="kv">
              <dt>Tài khoản</dt>
              <dd className="secret">{credentials.username}</dd>
              <dt>Mật khẩu</dt>
              <dd className="secret">{credentials.password}</dd>
              {credentials.otpNote && (
                <>
                  <dt>Mã OTP</dt>
                  <dd className="secret">{credentials.otpNote}</dd>
                </>
              )}
              <dt>Dùng được đến</dt>
              <dd>{formatDateTime(credentials.availableUntil)}</dd>
            </dl>
          )}
        </section>
      )}

      {canOtp && isRenter && (
        <section className="card stack">
          <h2>Mã OTP</h2>
          <p className="muted">Với game cần OTP, hãy yêu cầu chủ account gửi mã khi đăng nhập.</p>
          <button className="btn" disabled={busy} onClick={onRequestOtp}>
            Yêu cầu mã OTP
          </button>
        </section>
      )}

      {canOtp && isOwner && (
        <section id="otp" className="card stack">
          <h2>Gửi mã OTP cho người thuê</h2>
          <div className="field">
            <label htmlFor="otpNote">Mã OTP</label>
            <input id="otpNote" maxLength={64} value={otpNote} onChange={(e) => setOtpNote(e.target.value)} />
            <span className="field-hint">Mã sẽ tự hết hạn sau 10 phút.</span>
          </div>
          <button className="btn btn-primary" disabled={busy || !otpNote.trim()} onClick={onSendOtpNote}>
            Gửi mã
          </button>
        </section>
      )}

      <div id="disputes"><DisputesSection orderId={o.id} orderStatus={o.status} isRenter={isRenter} isOwner={isOwner} risks={orderRisks(o)} handoverConfirmedAt={o.handoverConfirmedAt} /></div>

      {(isRenter || isOwner || can('helpdesk:handle')) && <OrderSupportSection orderId={o.id} />}

      {canCancel && (
        <section className="row">
          <button className="btn btn-danger" disabled={busy} onClick={onCancel}>
            Hủy đơn
          </button>
        </section>
      )}
    </div>
  );
}
