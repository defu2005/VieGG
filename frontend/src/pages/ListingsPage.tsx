import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { api } from '../api/client';
import type { GameView, Listing, PageResponse } from '../api/types';
import { ListingCard, ListingSkeletons } from '../components/ListingCard';
import { PageHelp } from '../components/PageHelp';
import { errorMessage, localInputToIso } from '../lib/format';

const PRICE_PRESETS: { label: string; min?: number; max?: number }[] = [
  { label: 'Mọi mức giá' },
  { label: 'Dưới 20k', max: 20000 },
  { label: '20k – 50k', min: 20000, max: 50000 },
  { label: '50k – 100k', min: 50000, max: 100000 },
  { label: 'Trên 100k', min: 100000 },
];

export function ListingsPage() {
  const [params, setParams] = useSearchParams();
  const gameId = params.get('game') ?? '';
  const minPrice = params.get('min') ?? '';
  const maxPrice = params.get('max') ?? '';
  const from = params.get('from') ?? '';
  const to = params.get('to') ?? '';
  const [page, setPage] = useState(0);

  const [games, setGames] = useState<GameView[]>([]);
  const [result, setResult] = useState<PageResponse<Listing> | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [showTime, setShowTime] = useState(Boolean(from || to));

  /** Updates one filter in the URL (so searches are shareable) and returns to page 1. */
  const setFilter = (key: string, value: string | undefined) => {
    const next = new URLSearchParams(params);
    if (value) next.set(key, value);
    else next.delete(key);
    setParams(next, { replace: true });
    setPage(0);
  };

  const setPrice = (min?: number, max?: number) => {
    const next = new URLSearchParams(params);
    if (min) next.set('min', String(min));
    else next.delete('min');
    if (max) next.set('max', String(max));
    else next.delete('max');
    setParams(next, { replace: true });
    setPage(0);
  };

  useEffect(() => {
    api<GameView[]>('/games')
      .then(setGames)
      .catch((err) => setError(`Không tải được danh sách game: ${errorMessage(err)}`));
  }, []);

  const timeIncomplete = Boolean(from) !== Boolean(to);

  useEffect(() => {
    if (timeIncomplete) return;
    setResult(null);
    setError(null);
    const query = new URLSearchParams({ page: String(page), size: '12' });
    if (gameId) query.set('gameId', gameId);
    if (minPrice) query.set('minPrice', minPrice);
    if (maxPrice) query.set('maxPrice', maxPrice);
    if (from && to) {
      query.set('from', localInputToIso(from));
      query.set('to', localInputToIso(to));
    }
    let cancelled = false;
    api<PageResponse<Listing>>(`/listings/search?${query}`)
      .then((r) => !cancelled && setResult(r))
      .catch((err) => !cancelled && setError(errorMessage(err)));
    return () => {
      cancelled = true;
    };
  }, [gameId, minPrice, maxPrice, from, to, page, timeIncomplete]);

  const gameName = (id: string) => games.find((g) => g.id === id)?.name;
  const activePreset = PRICE_PRESETS.findIndex((p) => String(p.min ?? '') === minPrice && String(p.max ?? '') === maxPrice);

  return (
    <div className="stack">
      <h1>Tìm account</h1>
      <PageHelp id="listings" title="Cách thuê account">
        <p>Lọc theo game, giá và khung giờ bạn muốn chơi, rồi mở account để xem chi tiết. Giá hiển thị là giá mỗi giờ; khi đặt bạn trả thêm tiền cọc, được hoàn lại sau khi thuê xong.</p>
      </PageHelp>

      <section className="card stack">
        <div>
          <p className="muted" style={{ marginBottom: '0.4rem' }}>
            Game
          </p>
          <div className="chips">
            <button className={`chip ${gameId === '' ? 'active' : ''}`} onClick={() => setFilter('game', undefined)}>
              Tất cả
            </button>
            {games.map((g) => (
              <button key={g.id} className={`chip ${gameId === g.id ? 'active' : ''}`} onClick={() => setFilter('game', g.id)}>
                {g.name}
              </button>
            ))}
          </div>
        </div>

        <div>
          <p className="muted" style={{ marginBottom: '0.4rem' }}>
            Giá thuê mỗi giờ
          </p>
          <div className="chips">
            {PRICE_PRESETS.map((p, i) => (
              <button key={p.label} className={`chip ${activePreset === i ? 'active' : ''}`} onClick={() => setPrice(p.min, p.max)}>
                {p.label}
              </button>
            ))}
          </div>
        </div>

        <div>
          <button className="chip" onClick={() => setShowTime((v) => !v)} aria-expanded={showTime}>
            {showTime ? '▾' : '▸'} Chỉ hiện account còn trống theo giờ
          </button>
          {showTime && (
            <div className="row" style={{ marginTop: '0.6rem' }}>
              <div className="field">
                <label htmlFor="from">Từ</label>
                <input id="from" type="datetime-local" value={from} onChange={(e) => setFilter('from', e.target.value)} />
              </div>
              <div className="field">
                <label htmlFor="to">Đến</label>
                <input id="to" type="datetime-local" value={to} onChange={(e) => setFilter('to', e.target.value)} />
              </div>
              {timeIncomplete && <span className="error">Cần nhập cả hai mốc giờ.</span>}
              {(from || to) && (
                <button
                  className="btn btn-ghost"
                  onClick={() => {
                    const next = new URLSearchParams(params);
                    next.delete('from');
                    next.delete('to');
                    setParams(next, { replace: true });
                  }}
                >
                  Xóa lọc giờ
                </button>
              )}
            </div>
          )}
        </div>
      </section>

      {error && <p className="error">{error}</p>}
      {!result && !error && !timeIncomplete && <ListingSkeletons count={6} />}

      {result && (
        <p className="muted">
          {result.totalElements === 0 ? 'Không có account phù hợp.' : `Tìm thấy ${result.totalElements} account, xếp theo giá tăng dần.`}
        </p>
      )}

      <div className="grid">
        {result?.content.map((l) => (
          <ListingCard key={l.id} listing={l} gameName={gameName(l.gameId)} />
        ))}
      </div>

      {result && result.totalPages > 1 && (
        <div className="row spread">
          <button className="btn" disabled={page === 0} onClick={() => setPage(page - 1)}>
            Trang trước
          </button>
          <span className="muted">
            Trang {page + 1} / {result.totalPages}
          </span>
          <button className="btn" disabled={page + 1 >= result.totalPages} onClick={() => setPage(page + 1)}>
            Trang sau
          </button>
        </div>
      )}
    </div>
  );
}
