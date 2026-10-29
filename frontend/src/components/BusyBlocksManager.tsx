import { useEffect, useState, type FormEvent } from 'react';
import { api } from '../api/client';
import type { BusyBlock, Listing } from '../api/types';
import { errorMessage, formatDateTime, localInputToIso, toLocalInput } from '../lib/format';
import { nowMs } from '../lib/serverClock';

/** Start rounded up to the next quarter hour, so the default slot looks tidy. */
function nextQuarter(): Date {
  const d = new Date(nowMs());
  d.setSeconds(0, 0);
  d.setMinutes(Math.ceil((d.getMinutes() + 1) / 15) * 15);
  return d;
}

const hours = (n: number) => n * 60 * 60 * 1000;

/**
 * The owner's busy time on the listing's account: renters see it as taken and cannot book it.
 * Notes stay private to the owner.
 */
export function BusyBlocksManager({ listing }: { listing: Listing }) {
  const [blocks, setBlocks] = useState<BusyBlock[] | null>(null);
  const [startAt, setStartAt] = useState(() => toLocalInput(nextQuarter()));
  const [endAt, setEndAt] = useState(() => toLocalInput(new Date(nextQuarter().getTime() + hours(2))));
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api<BusyBlock[]>(`/listings/${listing.id}/busy-blocks`)
      .then(setBlocks)
      .catch((err) => setError(errorMessage(err)));
  }, [listing.id]);

  const preset = (from: Date, to: Date) => {
    setStartAt(toLocalInput(from));
    setEndAt(toLocalInput(to));
  };
  const now = new Date(nowMs());
  const endOfToday = new Date(now);
  endOfToday.setHours(23, 59, 0, 0);
  const tomorrow = new Date(now);
  tomorrow.setDate(now.getDate() + 1);
  tomorrow.setHours(0, 0, 0, 0);
  const tomorrowEnd = new Date(tomorrow);
  tomorrowEnd.setHours(23, 59, 0, 0);

  const onAdd = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    setBusy(true);
    try {
      const list = await api<BusyBlock[]>(`/listings/${listing.id}/busy-blocks`, {
        method: 'POST',
        body: { startAt: localInputToIso(startAt), endAt: localInputToIso(endAt), note: note.trim() || null },
      });
      setBlocks(list);
      setNote('');
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  };

  const onRemove = async (id: string) => {
    setError(null);
    setBusy(true);
    try {
      setBlocks(await api<BusyBlock[]>(`/listings/${listing.id}/busy-blocks/${id}`, { method: 'DELETE' }));
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="stack" style={{ gap: '0.5rem' }}>
      <p className="muted" style={{ margin: 0 }}>
        Trong giờ bận, người thuê thấy account đã kín lịch và không đặt được. Không thể đặt bận trùng đơn đã có người đặt.
      </p>
      {blocks === null ? (
        !error && <p className="muted">Đang tải…</p>
      ) : blocks.length === 0 ? (
        <p className="muted" style={{ margin: 0 }}>
          Chưa đặt giờ bận nào.
        </p>
      ) : (
        <ul style={{ paddingLeft: '1.1rem', margin: 0 }}>
          {blocks.map((b) => (
            <li key={b.id} style={{ marginBottom: '0.25rem' }}>
              {formatDateTime(b.startAt)} → {formatDateTime(b.endAt)}
              {b.note && <span className="muted"> · {b.note}</span>}{' '}
              <button type="button" className="btn btn-sm" disabled={busy} onClick={() => onRemove(b.id)}>
                Bỏ
              </button>
            </li>
          ))}
        </ul>
      )}
      <form className="stack" style={{ gap: '0.5rem' }} onSubmit={onAdd}>
        <div className="row" style={{ flexWrap: 'wrap', gap: '0.4rem' }}>
          <button type="button" className="btn btn-sm" onClick={() => preset(now, new Date(now.getTime() + hours(2)))}>
            2 giờ tới
          </button>
          <button type="button" className="btn btn-sm" onClick={() => preset(now, endOfToday)}>
            Hết hôm nay
          </button>
          <button type="button" className="btn btn-sm" onClick={() => preset(tomorrow, tomorrowEnd)}>
            Cả ngày mai
          </button>
        </div>
        <div className="grid">
          <div className="field">
            <label htmlFor={`bb-from-${listing.id}`}>Từ</label>
            <input
              id={`bb-from-${listing.id}`}
              type="datetime-local"
              required
              value={startAt}
              onChange={(e) => setStartAt(e.target.value)}
            />
          </div>
          <div className="field">
            <label htmlFor={`bb-to-${listing.id}`}>Đến</label>
            <input
              id={`bb-to-${listing.id}`}
              type="datetime-local"
              required
              min={startAt}
              value={endAt}
              onChange={(e) => setEndAt(e.target.value)}
            />
          </div>
          <div className="field">
            <label htmlFor={`bb-note-${listing.id}`}>Ghi chú (chỉ bạn thấy)</label>
            <input
              id={`bb-note-${listing.id}`}
              maxLength={200}
              placeholder="VD: tự chơi giải đấu"
              value={note}
              onChange={(e) => setNote(e.target.value)}
            />
          </div>
        </div>
        <div>
          <button className="btn btn-primary" disabled={busy}>
            Thêm giờ bận
          </button>
        </div>
      </form>
      {error && (
        <p className="error" role="alert" style={{ margin: 0 }}>
          {error}
        </p>
      )}
    </div>
  );
}
