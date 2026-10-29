import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import type { GameView, TosStatus } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { errorMessage, formatVnd } from '../lib/format';
import { HANDOVER_MODE, labelOf } from '../lib/labels';
import { FlashMessages, useFlash } from '../lib/useFlash';

/**
 * New games start as REVIEW_REQUIRED and stay hidden from users until staff check the publisher's ToS.
 * This queue on the Approvals page is the one place where that decision is made.
 */
export function GameReviewQueue() {
  const { can } = useAuth();
  const canDecide = can('game:change_tos');
  const [games, setGames] = useState<GameView[] | null>(null);
  const flash = useFlash();

  const load = useCallback(() => {
    api<GameView[]>('/games?all=true')
      .then((all) => setGames(all.filter((g) => g.tosStatus === 'REVIEW_REQUIRED')))
      .catch((err) => flash.setError(`Không tải được danh sách game chờ thẩm định: ${errorMessage(err)}`));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const decide = async (g: GameView, tosStatus: TosStatus) => {
    const allow = tosStatus === 'ALLOWED';
    const note = window.prompt(
      allow
        ? `Duyệt "${g.name}" cho phép cho thuê? Ghi chú (có thể để trống):`
        : `Lý do từ chối và cấm "${g.name}" (bắt buộc):`,
    );
    if (note === null) return; // cancelled
    if (!allow && !note.trim()) {
      flash.setError('Cần nhập lý do để từ chối game.');
      return;
    }
    const ok = await flash.run(
      () => api(`/admin/games/${g.id}/tos-status`, { method: 'POST', body: { tosStatus, note: note.trim() || undefined } }),
      allow
        ? `Đã duyệt ${g.name}. Game đã hiển thị cho người dùng và có thể đăng listing.`
        : `Đã từ chối ${g.name}. Game chuyển sang trạng thái bị cấm.`,
    );
    if (ok) load();
  };

  return (
    <section className="stack">
      <h2>Game chờ thẩm định điều khoản{games && games.length > 0 ? ` (${games.length})` : ''}</h2>
      <p className="muted" style={{ margin: 0 }}>
        Game mới chỉ hiển thị cho người dùng và nhận listing sau khi được duyệt tại đây.
      </p>
      <FlashMessages error={flash.error} notice={flash.notice} />
      {games && games.length === 0 && <p className="muted">Không có game nào chờ thẩm định.</p>}
      {games?.map((g) => (
        <div key={g.id} className="card stack">
          <div className="row spread">
            <strong>{g.name}</strong>
            <span className="badge badge-warn">Chờ thẩm định điều khoản</span>
          </div>
          <p className="muted" style={{ margin: 0 }}>
            {g.code}
            {g.publisher ? ` · ${g.publisher}` : ''} · {labelOf(HANDOVER_MODE, g.handoverMode)} · cọc {formatVnd(g.depositMin)}–{formatVnd(g.depositMax)} · phí{' '}
            {g.platformFeeBps / 100}% · tuổi tối thiểu {g.minAge}
          </p>
          <p style={{ margin: 0 }}>
            {g.tosUrl ? (
              <a href={g.tosUrl} target="_blank" rel="noreferrer">
                Xem điều khoản (ToS) của nhà phát hành
              </a>
            ) : (
              <span className="muted">Chưa có link ToS. </span>
            )}{' '}
            · <Link to="/admin/games">Xem / sửa chính sách</Link>
          </p>
          {canDecide ? (
            <div className="row">
              <button className="btn btn-primary" disabled={flash.busy} onClick={() => decide(g, 'ALLOWED')}>
                Duyệt — cho phép cho thuê
              </button>
              <button className="btn btn-danger" disabled={flash.busy} onClick={() => decide(g, 'PROHIBITED')}>
                Từ chối — cấm game
              </button>
            </div>
          ) : (
            <p className="muted" style={{ margin: 0 }}>
              Bạn không có quyền duyệt game (cần quyền game:change_tos).
            </p>
          )}
        </div>
      ))}
    </section>
  );
}
