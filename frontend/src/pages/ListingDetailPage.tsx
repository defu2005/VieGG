import { Fragment, useEffect, useMemo, useState, type FormEvent } from 'react';
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom';
import { api, newIdempotencyKey } from '../api/client';
import type { GameView, ListingDetail, PayResponse } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { ListingGallery } from '../components/ListingImages';
import { ListingRisks } from '../components/ListingRisks';
import { WalletBalanceLine } from '../components/WalletBalanceLine';
import { attributeTags } from '../lib/attributes';
import { errorMessage, formatDateTime, formatVnd, localInputToIso, toLocalInput } from '../lib/format';
import { durationLabel, freeCancelLabel, priceSummary, rentOptions } from '../lib/pricing';
import { nowMs } from '../lib/serverClock';

const ENDMODE_LABEL: Record<string, string> = {
  FORCE_AT_END: 'Hết giờ là đổi mật khẩu ngay',
  GRACE_FOR_CURRENT_MATCH: 'Được thêm thời gian để kết thúc trận đang chơi',
  STAY_ONLINE_UNTIL_RECLAIM: 'Giữ đăng nhập đến khi chủ account lấy lại',
};

export function ListingDetailPage() {
  const { id = '' } = useParams();
  const { me, isStaff } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();

  const [data, setData] = useState<ListingDetail | null>(null);
  const [game, setGame] = useState<GameView | null>(null);
  const [error, setError] = useState<string | null>(null);

  const [durationMinutes, setDurationMinutes] = useState<number | null>(null);
  // 'now' = the rental starts as soon as it is paid; 'later' = booked for a chosen time, usable only from then.
  const [startMode, setStartMode] = useState<'now' | 'later'>('now');
  const [startAt, setStartAt] = useState(() => toLocalInput(new Date(nowMs() + 24 * 60 * 60 * 1000)));
  const [orderError, setOrderError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    api<ListingDetail>(`/listings/${id}`)
      .then((d) => {
        setData(d);
        const opts = rentOptions(d.listing, d.policy.minDurationMinutes, d.policy.maxDurationMinutes);
        // "Thuê lại" from a past order passes ?duration= to preselect the same package.
        const wanted = Number(new URLSearchParams(location.search).get('duration'));
        setDurationMinutes(opts.find((o) => o.durationMinutes === wanted)?.durationMinutes ?? opts[0]?.durationMinutes ?? null);
        api<GameView>(`/games/${d.listing.gameId}`).then(setGame).catch(() => setGame(null));
      })
      .catch((err) => setError(errorMessage(err)));
  }, [id]);

  const startDate = useMemo(() => (startMode === 'now' ? new Date(nowMs()) : new Date(startAt)), [startMode, startAt]);
  const inPast = startMode === 'later' && !(startDate.getTime() > nowMs());

  // Every end mode except FORCE_AT_END keeps the account busy for the grace minutes after the end (the server
  // blocks [start, end + grace) on the calendar), so the new booking needs that extra time free as well.
  const bufferMinutes = data && data.listing.endMode !== 'FORCE_AT_END' ? data.policy.graceMinutes : 0;

  const conflict = useMemo(() => {
    if (!data || durationMinutes == null || Number.isNaN(startDate.getTime())) return null;
    const end = new Date(startDate.getTime() + (durationMinutes + bufferMinutes) * 60 * 1000);
    return data.busyPeriods.find((b) => startDate < new Date(b.endAt) && end > new Date(b.startAt)) ?? null;
  }, [data, durationMinutes, startDate, bufferMinutes]);

  const onOrder = async (e: FormEvent) => {
    e.preventDefault();
    if (!me) {
      navigate('/login', { state: { from: location.pathname } });
      return;
    }
    setOrderError(null);
    setSubmitting(true);
    try {
      const order = await api<{ id: string }>('/orders', {
        method: 'POST',
        // Without startAt the rental starts right after payment; with it, only from that time.
        body: startMode === 'now' ? { listingId: id, durationMinutes } : { listingId: id, durationMinutes, startAt: localInputToIso(startAt) },
        idempotencyKey: newIdempotencyKey(),
      });
      // Booking = paying from the wallet right away instead of leaving an unpaid order.
      let payError: string | null = null;
      try {
        await api<PayResponse>(`/orders/${order.id}/pay`, { method: 'POST', idempotencyKey: newIdempotencyKey() });
      } catch (err) {
        // Usually not enough balance: the order page shows why and still offers the pay button.
        payError = errorMessage(err);
      }
      navigate(`/orders/${order.id}`, { state: { payError } });
    } catch (err) {
      setOrderError(errorMessage(err));
    } finally {
      setSubmitting(false);
    }
  };

  if (error) return <p className="error">{error}</p>;
  if (!data) return <div className="skeleton" style={{ minHeight: '18rem' }} aria-busy="true" />;

  const { listing: l, policy, busyPeriods, bookable } = data;
  const isOwner = me?.id === l.ownerId;
  const options = rentOptions(l, policy.minDurationMinutes, policy.maxDurationMinutes);
  const selected = options.find((o) => o.durationMinutes === durationMinutes) ?? null;
  const rent = selected?.price ?? 0;
  const total = rent + l.depositAmount;
  const tags = attributeTags(l.attributes, 12);
  const price = priceSummary(l);

  return (
    <div className="stack">
      <p>
        <Link to="/listings">← Quay lại tìm kiếm</Link>
      </p>
      <div className="detail-layout">
        <section className="card stack">
          <ListingGallery listing={l} />
          <div className="row">
            {game && <span className="badge">{game.name}</span>}
            {l.autoAccept && <span className="tag tag-hot">⚡ Nhận đơn ngay, không cần chờ duyệt</span>}
          </div>
          <h1>{l.title}</h1>
          <p className="price">
            {price.from && <small>từ </small>}
            {formatVnd(price.amount)}
            <small> / {price.unit} · cọc {formatVnd(l.depositAmount)} (hoàn lại sau khi kết thúc)</small>
          </p>
          {l.pricingTiers?.length > 0 && (
            <>
              <h2>Bảng giá thuê</h2>
              <dl className="kv">
                {l.pricingTiers.map((t) => (
                  <Fragment key={t.durationMinutes}>
                    <dt>{durationLabel(t.durationMinutes)}</dt>
                    <dd>{formatVnd(t.price)}</dd>
                  </Fragment>
                ))}
              </dl>
            </>
          )}
          {tags.length > 0 && (
            <div className="tags">
              {tags.map((t) => (
                <span key={t} className="tag">
                  {t}
                </span>
              ))}
            </div>
          )}
          {l.description && <p style={{ whiteSpace: 'pre-line' }}>{l.description}</p>}

          <h2>Quy định thuê</h2>
          <dl className="kv">
            <dt>Bắt đầu</dt>
            <dd>
              Thuê ngay ({l.autoAccept ? 'dùng được khi thanh toán xong' : 'dùng được khi chủ account duyệt'}) hoặc hẹn giờ (dùng được đúng giờ hẹn)
            </dd>
            <dt>Cách bàn giao</dt>
            <dd>{policy.handoverMode === 'OWNER_ASSISTED_OTP' ? 'Chủ account hỗ trợ gửi mã OTP khi bạn đăng nhập' : 'Xem tài khoản và mật khẩu trong đơn khi đến giờ'}</dd>
            <dt>Khi hết giờ</dt>
            <dd>
              {ENDMODE_LABEL[l.endMode] ?? l.endMode}
              {bufferMinutes > 0 && ` (thêm tối đa ${bufferMinutes} phút sau giờ kết thúc, lịch được giữ cho bạn trong thời gian này)`}
            </dd>
            <dt>Hủy miễn phí</dt>
            <dd>
              Hoàn 100% {freeCancelLabel(policy.renterFreeCancelMinutes)}
              {policy.renterFreeCancelMinutes > 0 && `; hủy muộn hơn hoàn ${policy.lateCancelRefundBps / 100}% tiền thuê`}. Cọc luôn được hoàn.
            </dd>
            <dt>Khiếu nại</dt>
            <dd>Trong {policy.complaintWindowMinutes} phút sau khi nhận account</dd>
            <dt>Cọc được giữ</dt>
            <dd>Tối đa {policy.depositHoldHours} giờ sau khi kết thúc để đối chiếu</dd>
            <dt>Độ tuổi</dt>
            <dd>Từ {policy.minAge} tuổi</dd>
          </dl>
          <ListingRisks listing={l} risks={data.risks ?? []} />
        </section>

        <aside className="card stack">
          <h2>Đặt thuê</h2>
          {!bookable ? (
            <p className="muted">Account này hiện chưa nhận đơn.</p>
          ) : isOwner ? (
            <p className="muted">Đây là listing của bạn.</p>
          ) : isStaff ? (
            <p className="muted">Tài khoản nhân viên không đặt thuê được. Dùng tài khoản cá nhân nếu muốn thuê.</p>
          ) : (
            <form className="form" onSubmit={onOrder}>
              <div className="field">
                <span id="dur-label" className="muted">
                  Chọn gói thuê
                </span>
                <div className="chips" role="group" aria-labelledby="dur-label">
                  {options.map((o) => (
                    <button
                      type="button"
                      key={o.durationMinutes}
                      className={`chip ${durationMinutes === o.durationMinutes ? 'active' : ''}`}
                      aria-pressed={durationMinutes === o.durationMinutes}
                      onClick={() => setDurationMinutes(o.durationMinutes)}
                    >
                      {durationLabel(o.durationMinutes)} · {formatVnd(o.price)}
                    </button>
                  ))}
                </div>
              </div>

              <div className="field">
                <span id="start-label" className="muted">
                  Bắt đầu
                </span>
                <div className="chips" role="group" aria-labelledby="start-label">
                  <button type="button" className={`chip ${startMode === 'now' ? 'active' : ''}`} aria-pressed={startMode === 'now'} onClick={() => setStartMode('now')}>
                    Thuê ngay
                  </button>
                  <button type="button" className={`chip ${startMode === 'later' ? 'active' : ''}`} aria-pressed={startMode === 'later'} onClick={() => setStartMode('later')}>
                    Hẹn giờ
                  </button>
                </div>
                {startMode === 'later' && (
                  <input aria-labelledby="start-label" type="datetime-local" required min={toLocalInput(new Date(nowMs()))} value={startAt} onChange={(e) => setStartAt(e.target.value)} />
                )}
                <span className="field-hint">
                  {startMode === 'now'
                    ? l.autoAccept
                      ? 'Thanh toán xong là dùng được ngay, thời gian thuê tính từ lúc thanh toán.'
                      : 'Dùng được ngay khi chủ account duyệt đơn, thời gian thuê tính từ lúc duyệt.'
                    : 'Thanh toán ngay khi đặt để giữ lịch. Chỉ nhận được account khi đến đúng giờ đã hẹn.'}
                </span>
                {startMode === 'later' && durationMinutes != null && !inPast && !Number.isNaN(startDate.getTime()) && (
                  <span className="field-hint">
                    {formatDateTime(startDate.toISOString())} → {formatDateTime(new Date(startDate.getTime() + durationMinutes * 60000).toISOString())}
                    {bufferMinutes > 0 &&
                      ` · lịch giữ đến ${formatDateTime(new Date(startDate.getTime() + (durationMinutes + bufferMinutes) * 60000).toISOString())} (gồm ${bufferMinutes} phút ân hạn)`}
                  </span>
                )}
              </div>

              <div className="summary" aria-live="polite">
                <div className="line">
                  <span>Tiền thuê{selected ? ` (${durationLabel(selected.durationMinutes)})` : ''}</span>
                  <span>{formatVnd(rent)}</span>
                </div>
                <div className="line">
                  <span>Tiền cọc (hoàn lại)</span>
                  <span>{formatVnd(l.depositAmount)}</span>
                </div>
                <div className="line total">
                  <span>Thanh toán</span>
                  <span>{formatVnd(total)}</span>
                </div>
              </div>

              {me && !isOwner && <WalletBalanceLine amount={total} />}
              {inPast && <p className="banner">Giờ hẹn đã qua, hãy chọn giờ khác hoặc chọn "Thuê ngay".</p>}
              {conflict && (
                <p className="banner">
                  Trùng lịch đã có người đặt ({formatDateTime(conflict.startAt)} → {formatDateTime(conflict.endAt)})
                  {bufferMinutes > 0 && `, tính cả ${bufferMinutes} phút ân hạn sau giờ kết thúc của bạn`}. Hãy chọn gói ngắn hơn hoặc giờ khác.
                </p>
              )}
              {orderError && <p className="error">{orderError}</p>}
              <button className="btn btn-primary" disabled={submitting || !selected || inPast || Boolean(conflict)}>
                {submitting ? 'Đang đặt…' : me ? `Đặt và thanh toán · ${formatVnd(total)}` : 'Đăng nhập để đặt thuê'}
              </button>
              <p className="muted" style={{ margin: 0 }}>
                {l.autoAccept
                  ? 'Đặt là trừ tiền ví luôn. Đến giờ thuê là nhận account ngay trong trang đơn.'
                  : 'Đặt là trừ tiền ví luôn, sau đó chủ account duyệt đơn. Không duyệt kịp sẽ hoàn tiền về ví.'}
              </p>
            </form>
          )}

          <h3>Lịch đã bận</h3>
          {busyPeriods.length > 0 && bufferMinutes > 0 && (
            <p className="muted" style={{ margin: 0 }}>
              Đã gồm thời gian ân hạn sau mỗi đơn.
            </p>
          )}
          {busyPeriods.length === 0 ? (
            <p className="muted">Chưa có lịch đặt nào, bạn có thể chọn giờ tùy ý.</p>
          ) : (
            <ul className="muted" style={{ paddingLeft: '1.1rem', margin: 0 }}>
              {busyPeriods.map((b) => (
                <li key={b.startAt}>
                  {formatDateTime(b.startAt)} → {formatDateTime(b.endAt)}
                  {b.kind === 'OWNER_BUSY' && ' (chủ account bận)'}
                </li>
              ))}
            </ul>
          )}
        </aside>
      </div>
    </div>
  );
}
