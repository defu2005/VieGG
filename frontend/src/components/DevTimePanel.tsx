import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api, saveTokens } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import { errorMessage, formatDateTime, localInputToIso, toLocalInput } from '../lib/format';
import { nowMs, syncServerNow } from '../lib/serverClock';

/**
 * Dev only (rendered when import.meta.env.DEV): moves the backend's business clock to test time-based flows
 * (payment expiry, rental start/end, reminders, seller hold, deposit release...).
 */

interface ClockStatus {
  now: string;
  realNow: string;
  offsetSeconds: number;
  frozen: boolean;
}

interface Deadline {
  kind: string;
  orderId: string | null;
  listingTitle: string | null;
  renterId: string | null;
  ownerId: string | null;
  at: string;
}

interface TravelResponse {
  status: ClockStatus;
  from: string;
  deadlineSteps: number;
  tokens: { accessToken: string; refreshToken: string };
}

const KIND_LABEL: Record<string, string> = {
  PAYMENT_EXPIRES: 'Hết hạn thanh toán',
  APPROVAL_EXPIRES: 'Hết hạn chủ duyệt',
  RENTAL_STARTS: 'Bắt đầu thuê',
  ENDING_REMINDER: 'Nhắc sắp hết giờ',
  RENTAL_ENDS: 'Kết thúc thuê',
  EXTENSION_PAYMENT_EXPIRES: 'Hết hạn thanh toán gia hạn',
  SELLER_HOLD_RELEASE: 'Giải ngân cho chủ',
  DEPOSIT_RELEASE: 'Hoàn cọc',
  ROTATION_OVERDUE: 'Quá hạn đổi mật khẩu',
};

const QUICK: { label: string; duration: string }[] = [
  { label: '+10 phút', duration: 'PT10M' },
  { label: '+1 giờ', duration: 'PT1H' },
  { label: '+6 giờ', duration: 'PT6H' },
  { label: '+1 ngày', duration: 'P1D' },
  { label: '+3 ngày', duration: 'P3D' },
  { label: '+7 ngày', duration: 'P7D' },
];

const UNIT: Record<string, string> = { M: 'phút', H: 'giờ', D: 'ngày' };
const OPEN_KEY = 'rentgg.devTimePanelOpen';
const FLASH_KEY = 'rentgg.devTimeFlash';

function readSession(key: string): string | null {
  try {
    return sessionStorage.getItem(key);
  } catch {
    return null;
  }
}

function writeSession(key: string, value: string | null): void {
  try {
    if (value === null) sessionStorage.removeItem(key);
    else sessionStorage.setItem(key, value);
  } catch {
    // Storage blocked: the panel simply forgets its state on reload.
  }
}

/** "+2 ngày 3 giờ", "-45 phút", "giờ thật". */
function offsetLabel(seconds: number): string {
  if (Math.abs(seconds) < 60) return 'giờ thật';
  const sign = seconds < 0 ? '-' : '+';
  let rest = Math.abs(seconds);
  const d = Math.floor(rest / 86400);
  rest -= d * 86400;
  const h = Math.floor(rest / 3600);
  rest -= h * 3600;
  const m = Math.floor(rest / 60);
  const parts = [d && `${d} ngày`, h && `${h} giờ`, !d && m && `${m} phút`].filter(Boolean);
  return sign + parts.join(' ');
}

function relative(iso: string): string {
  return offsetLabel(Math.round((new Date(iso).getTime() - nowMs()) / 1000)).replace('+', 'sau ');
}

function isoDuration(amount: number, unit: string): string {
  const sign = amount < 0 ? '-' : '';
  const n = Math.abs(amount);
  return unit === 'D' ? `${sign}P${n}D` : `${sign}PT${n}${unit}`;
}

export function DevTimePanel() {
  const { me, can } = useAuth();
  const [open, setOpen] = useState(() => readSession(OPEN_KEY) === '1');
  const [status, setStatus] = useState<ClockStatus | null>(null);
  const [upcoming, setUpcoming] = useState<Deadline[] | null>(null);
  const [mineOnly, setMineOnly] = useState(true);
  const [stepThrough, setStepThrough] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [flash, setFlash] = useState<string | null>(() => readSession(FLASH_KEY));
  const [amount, setAmount] = useState(2);
  const [unit, setUnit] = useState('H');
  const [target, setTarget] = useState('');
  const [, tick] = useState(0);

  const loadStatus = useCallback(async () => {
    const s = await api<ClockStatus>('/dev/time');
    syncServerNow(s.now);
    setStatus(s);
  }, []);

  const loadUpcoming = useCallback(async () => {
    setUpcoming(await api<Deadline[]>(`/dev/time/upcoming?mine=${mineOnly}&limit=15`));
  }, [mineOnly]);

  useEffect(() => {
    if (!me) return;
    loadStatus().catch(() => setStatus(null));
  }, [me, loadStatus]);

  useEffect(() => {
    if (!me || !open) return;
    loadUpcoming().catch((e) => setError(errorMessage(e)));
  }, [me, open, loadUpcoming]);

  // Keep the displayed clock ticking.
  useEffect(() => {
    const t = setInterval(() => tick((n) => n + 1), 1000);
    return () => clearInterval(t);
  }, []);

  useEffect(() => {
    writeSession(OPEN_KEY, open ? '1' : null);
  }, [open]);

  useEffect(() => {
    if (!flash) return;
    writeSession(FLASH_KEY, null);
    const t = setTimeout(() => setFlash(null), 6000);
    return () => clearTimeout(t);
  }, [flash]);

  if (!me) return null;

  /** Applies a clock move, keeps the session alive with the reissued tokens, then reloads so pages refetch. */
  const travel = async (path: string, body: unknown, describe: (r: TravelResponse) => string) => {
    setBusy(true);
    setError(null);
    try {
      const r = await api<TravelResponse>(path, { method: 'POST', body });
      saveTokens({ accessToken: r.tokens.accessToken, refreshToken: r.tokens.refreshToken });
      syncServerNow(r.status.now);
      writeSession(FLASH_KEY, describe(r));
      window.location.reload();
    } catch (e) {
      setError(errorMessage(e));
      setBusy(false);
    }
  };

  const describeMove = (r: TravelResponse) =>
    `Đã tua tới ${formatDateTime(r.status.now)}` +
    (r.deadlineSteps > 0 ? `, chạy job qua ${r.deadlineSteps} mốc trên đường.` : '.');

  const advance = (duration: string) =>
    travel('/dev/time/advance', { duration, stepThroughDeadlines: stepThrough, runJobs: true }, describeMove);

  const setAt = (atIso: string) =>
    travel('/dev/time/set', { at: atIso, stepThroughDeadlines: stepThrough, runJobs: true }, describeMove);

  const runJobs = async () => {
    setBusy(true);
    setError(null);
    try {
      await api('/dev/jobs/run-all', { method: 'POST' });
      writeSession(FLASH_KEY, 'Đã chạy tất cả job một lần.');
      window.location.reload();
    } catch (e) {
      setError(errorMessage(e));
      setBusy(false);
    }
  };

  const offset = status?.offsetSeconds ?? 0;
  const shifted = Math.abs(offset) >= 60 || !!status?.frozen;
  const shownNow = status?.frozen ? new Date(status.now).getTime() : nowMs();

  return (
    <div className="devtime">
      {open && (
        <div className="devtime-panel card stack" role="dialog" aria-label="Tua thời gian (dev)">
          <div className="devtime-head">
            <strong>Tua thời gian · dev</strong>
            <button className="btn btn-ghost btn-sm" onClick={() => setOpen(false)} aria-label="Đóng">
              ✕
            </button>
          </div>

          {status && (
            <div className="devtime-now">
              <div>
                Giờ server: <strong>{formatDateTime(new Date(shownNow).toISOString())}</strong>
                {status.frozen && <span className="badge devtime-frozen">Đóng băng</span>}
              </div>
              <div className="muted">
                Giờ thật: {formatDateTime(new Date().toISOString())} · lệch {offsetLabel(offset)}
              </div>
            </div>
          )}

          {flash && <div className="devtime-flash">{flash}</div>}
          {error && <div className="error">{error}</div>}

          <div className="devtime-row">
            {QUICK.map((q) => (
              <button key={q.duration} className="btn btn-sm" disabled={busy} onClick={() => advance(q.duration)}>
                {q.label}
              </button>
            ))}
          </div>

          <div className="devtime-row">
            <input
              type="number"
              className="devtime-amount"
              value={amount}
              onChange={(e) => setAmount(Number(e.target.value))}
              aria-label="Số lượng"
            />
            <select value={unit} onChange={(e) => setUnit(e.target.value)} aria-label="Đơn vị">
              {Object.entries(UNIT).map(([k, v]) => (
                <option key={k} value={k}>
                  {v}
                </option>
              ))}
            </select>
            <button
              className="btn btn-primary btn-sm"
              disabled={busy || !amount}
              onClick={() => advance(isoDuration(Math.trunc(amount), unit))}
            >
              {amount < 0 ? 'Lùi' : 'Tua'}
            </button>
          </div>

          <div className="devtime-row">
            <input
              type="datetime-local"
              value={target}
              onChange={(e) => setTarget(e.target.value)}
              aria-label="Đặt thời điểm"
            />
            <button className="btn btn-sm" disabled={busy || !target} onClick={() => setAt(localInputToIso(target))}>
              Đặt giờ
            </button>
          </div>

          <label className="devtime-check">
            <input type="checkbox" checked={stepThrough} onChange={(e) => setStepThrough(e.target.checked)} />
            Dừng ở từng mốc trên đường và chạy job (giữ đúng thứ tự: kết thúc thuê → giải ngân → hoàn cọc)
          </label>

          <div className="devtime-row">
            <button
              className="btn btn-sm"
              disabled={busy}
              onClick={() =>
                travel('/dev/time/freeze', { frozen: !status?.frozen }, (r) =>
                  r.status.frozen ? 'Đã đóng băng đồng hồ server.' : 'Đồng hồ server chạy tiếp.',
                )
              }
            >
              {status?.frozen ? 'Cho chạy tiếp' : 'Đóng băng'}
            </button>
            <button
              className="btn btn-sm"
              disabled={busy || !shifted}
              onClick={() => travel('/dev/time/reset', {}, () => 'Đã về giờ thật.')}
            >
              Về giờ thật
            </button>
            {can('dev:run_jobs') && (
              <button className="btn btn-sm" disabled={busy} onClick={runJobs}>
                Chạy job ngay
              </button>
            )}
          </div>

          <div className="devtime-upcoming">
            <div className="devtime-head">
              <strong>Mốc sắp tới</strong>
              <label className="devtime-check">
                <input type="checkbox" checked={mineOnly} onChange={(e) => setMineOnly(e.target.checked)} />
                Chỉ đơn của tôi
              </label>
            </div>
            {upcoming === null ? (
              <div className="muted">Đang tải…</div>
            ) : upcoming.length === 0 ? (
              <div className="muted">Không có mốc nào.</div>
            ) : (
              <ul>
                {upcoming.map((d, i) => (
                  <li key={`${d.kind}-${d.orderId}-${i}`}>
                    <div>
                      <strong>{KIND_LABEL[d.kind] ?? d.kind}</strong>
                      {d.orderId && (
                        <>
                          {' · '}
                          <Link to={`/orders/${d.orderId}`}>{d.listingTitle ?? 'Đơn'}</Link>
                        </>
                      )}
                      <div className="muted">
                        {formatDateTime(d.at)} ({relative(d.at)})
                      </div>
                    </div>
                    <button
                      className="btn btn-sm"
                      disabled={busy}
                      // One second past the deadline so the job query (<= now) picks it up.
                      onClick={() => setAt(new Date(new Date(d.at).getTime() + 1000).toISOString())}
                    >
                      Tới mốc
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </div>
        </div>
      )}

      <button
        className={`devtime-pill${shifted ? ' devtime-pill-shifted' : ''}`}
        onClick={() => {
          if (!open) setTarget(toLocalInput(new Date(nowMs() + 60 * 60 * 1000)));
          setOpen(!open);
        }}
        title="Tua thời gian (chỉ có ở môi trường dev)"
      >
        🕒 {formatDateTime(new Date(shownNow).toISOString())}
        {shifted && <span> · {status?.frozen ? 'đóng băng' : offsetLabel(offset)}</span>}
      </button>
    </div>
  );
}
