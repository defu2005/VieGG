import { useEffect, useState } from 'react';
import { api } from '../api/client';
import type { GameAccount, GameView, Listing, ListingStatus, PageResponse } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { Pager, pageAfterShrink } from '../components/Pager';
import { StatusBadge } from '../components/StatusBadge';
import { UserRef } from '../components/UserRef';
import { ListingImg } from '../components/ListingImages';
import { PageHelp } from '../components/PageHelp';
import { ReasonPicker } from '../components/ReasonPicker';
import { errorMessage, formatDateTime, formatVnd } from '../lib/format';
import { END_MODE, labelOf, LISTING_STATUS } from '../lib/labels';

const STATUSES: ListingStatus[] = ['PENDING_REVIEW', 'ACTIVE', 'PAUSED', 'REJECTED', 'SUSPENDED'];

export function AdminListingsPage() {
  const { can } = useAuth();
  const canReview = can('listing:approve');
  const canSuspend = can('listing:suspend');
  const canVerify = can('game_account:verify_recovery');
  const [status, setStatus] = useState<ListingStatus>('PENDING_REVIEW');
  const [page, setPage] = useState(0);
  const [result, setResult] = useState<PageResponse<Listing> | null>(null);
  const items = result?.content ?? null;
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [notes, setNotes] = useState<Record<string, string>>({});
  const [reloadKey, setReloadKey] = useState(0);

  useEffect(() => {
    setError(null);
    api<PageResponse<Listing>>(`/admin/listings?status=${status}&page=${page}&size=20`)
      .then((r) => {
        const back = pageAfterShrink(r, page);
        if (back != null) setPage(back);
        else setResult(r);
      })
      .catch((err) => setError(errorMessage(err)));
  }, [status, page, reloadKey]);

  /** Runs a moderation action on one listing, then refreshes the current filter. */
  const act = async (fn: () => Promise<unknown>, msg: string) => {
    setError(null);
    setNotice(null);
    try {
      await fn();
      setNotice(msg);
      setReloadKey((k) => k + 1);
    } catch (err) {
      setError(errorMessage(err));
    }
  };

  return (
    <div className="stack">
      <div className="row spread">
        <h1>Kiểm duyệt bài đăng</h1>
        <select aria-label="Trạng thái" value={status} onChange={(e) => {
            setStatus(e.target.value as ListingStatus);
            setPage(0);
            setResult(null);
          }} style={{ width: 'auto' }}>
          {STATUSES.map((s) => (
            <option key={s} value={s}>
              {labelOf(LISTING_STATUS, s)}
            </option>
          ))}
        </select>
      </div>
      <PageHelp id="admin-listings" title="Kiểm duyệt bài đăng cho thuê">
        <ol>
          <li>Kiểm tra tiêu đề, mô tả, giá có hợp lý và không gây hiểu nhầm.</li>
          <li>Account mới nằm ở mục “Account chờ xác minh”: bấm “Xác minh” sau khi đã kiểm tra chủ account sở hữu email / số điện thoại / 2FA khôi phục. Chủ account chỉ gửi duyệt listing được sau bước này.</li>
          <li>Bấm “Duyệt” để bài đăng hiển thị, hoặc nhập lý do rồi “Từ chối” để chủ account sửa lại.</li>
        </ol>
        <p>Bài đăng đang hiển thị có thể bị đình chỉ nếu vi phạm. “Gắn cờ nghi ngờ” sẽ khóa account game để rà soát bảo mật.</p>
      </PageHelp>

      {error && <p className="error">{error}</p>}
      {notice && <p className="success">{notice}</p>}

      {canVerify && <PendingAccounts onDone={setNotice} onError={(err) => setError(errorMessage(err))} />}

      <h2 style={{ fontSize: '1.1rem', margin: 0 }}>Bài đăng</h2>
      {items && items.length === 0 && <p className="muted">Không có listing ở trạng thái này.</p>}

      {items?.map((l) => (
        <div key={l.id} className="card stack">
          <div className="row spread">
            <div>
              <h2 style={{ fontSize: '1.05rem', marginBottom: '0.2rem' }}>{l.title}</h2>
              <p className="muted" style={{ margin: 0 }}>
                {formatVnd(l.pricePerHour)} / giờ · cọc {formatVnd(l.depositAmount)} · {labelOf(END_MODE, l.endMode)}
              </p>
            </div>
            <StatusBadge status={l.status} />
          </div>
          {l.description && <p style={{ whiteSpace: 'pre-line' }}>{l.description}</p>}
          {(l.ownerRisks?.length ?? 0) > 0 && (
            <div>
              <strong>Rủi ro chủ account tự thêm</strong>
              <ul className="risk-list">
                {l.ownerRisks!.map((r) => {
                  const amount = l.riskTerms?.find((t) => t.riskId === r.id)?.amount ?? null;
                  return (
                    <li key={r.id}>
                      {r.name} · {r.side === 'OWNER' ? `cọc ${amount != null ? formatVnd(amount) : 'không'}` : `đền bù ${amount != null ? formatVnd(amount) : 'không (chỉ hoàn tiền thuê)'}`}
                      {r.description && <span className="muted"> — {r.description}</span>}
                    </li>
                  );
                })}
              </ul>
            </div>
          )}
          {l.imageIds?.length > 0 && (
            <div className="image-grid" aria-label="Ảnh account">
              {l.imageIds.map((imageId, i) => (
                <div key={imageId} className="image-tile">
                  <ListingImg listingId={l.id} imageId={imageId} alt={`Ảnh ${i + 1}`} />
                </div>
              ))}
            </div>
          )}

          {canReview && status === 'PENDING_REVIEW' && (
            <div className="row">
              <button
                className="btn"
                onClick={() =>
                  act(() => api(`/admin/game-accounts/${l.gameAccountId}/verify-recovery`, { method: 'POST' }), 'Đã xác minh thông tin khôi phục của account.')
                }
              >
                Xác minh khôi phục
              </button>
              <button className="btn btn-primary" onClick={() => act(() => api(`/admin/listings/${l.id}/approve`, { method: 'POST' }), 'Đã duyệt listing.')}>
                Duyệt
              </button>
              <input
                aria-label="Lý do từ chối"
                placeholder="Lý do từ chối"
                value={notes[l.id] ?? ''}
                onChange={(e) => setNotes({ ...notes, [l.id]: e.target.value })}
                style={{ maxWidth: 320 }}
              />
              <button
                className="btn btn-danger"
                disabled={!notes[l.id]?.trim()}
                onClick={() =>
                  act(
                    () => api(`/admin/listings/${l.id}/reject`, { method: 'POST', body: { note: notes[l.id] } }),
                    'Đã từ chối listing.',
                  )
                }
              >
                Từ chối
              </button>
            </div>
          )}

          <div className="row">
            <span>Chủ account: <UserRef id={l.ownerId} /></span>
            <button
              className="btn btn-ghost"
              onClick={() => {
                const reasonCode = window.prompt('Vì sao nghi ngờ account này? (account sẽ bị tạm khóa để rà soát)');
                if (reasonCode?.trim()) {
                  act(
                    () => api(`/admin/game-accounts/${l.gameAccountId}/flag-suspicious`, { method: 'POST', body: { reasonCode } }),
                    'Đã gắn cờ account nghi ngờ.',
                  );
                }
              }}
            >
              Gắn cờ account nghi ngờ
            </button>
          </div>

          {canSuspend && (status === 'ACTIVE' || status === 'PAUSED') && (
            <div className="row" style={{ alignItems: 'flex-end' }}>
              <ReasonPicker
                id={`susp-${l.id}`}
                label="Lý do đình chỉ"
                value={notes[l.id] ?? ''}
                onChange={(v) => setNotes({ ...notes, [l.id]: v })}
                options={['SECURITY_REVIEW', 'SUSPECTED_FRAUD', 'STOLEN_ACCOUNT_REPORT', 'TOS_VIOLATION', 'MISLEADING_LISTING']}
              />
              <button
                className="btn btn-danger"
                disabled={!notes[l.id]?.trim()}
                onClick={() =>
                  act(
                    () => api(`/admin/listings/${l.id}/suspend`, { method: 'POST', body: { reasonCode: notes[l.id] } }),
                    'Đã đình chỉ listing.',
                  )
                }
              >
                Đình chỉ
              </button>
            </div>
          )}
        </div>
      ))}
      <Pager result={result} page={page} onPage={setPage} />
    </div>
  );
}

/**
 * Accounts waiting for recovery verification. Owners can only submit a listing once its account is verified,
 * so these never show up in the listing queue on their own.
 */
function PendingAccounts({ onDone, onError }: { onDone: (msg: string) => void; onError: (err: unknown) => void }) {
  const [page, setPage] = useState(0);
  const [result, setResult] = useState<PageResponse<GameAccount> | null>(null);
  const accounts = result?.content ?? null;
  const [games, setGames] = useState<Record<string, string>>({});
  const [busyId, setBusyId] = useState<string | null>(null);

  const load = (p = page) =>
    api<PageResponse<GameAccount>>(`/admin/game-accounts?state=PENDING_VERIFICATION&page=${p}&size=20`)
      .then((r) => {
        const back = pageAfterShrink(r, p);
        if (back != null) setPage(back);
        else setResult(r);
      })
      .catch(onError);

  useEffect(() => {
    load(page);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [page]);

  useEffect(() => {
    api<GameView[]>('/games?all=true')
      .then((gs) => setGames(Object.fromEntries(gs.map((g) => [g.id, g.name]))))
      .catch(() => undefined);
  }, []);

  const verify = async (id: string) => {
    setBusyId(id);
    try {
      await api(`/admin/game-accounts/${id}/verify-recovery`, { method: 'POST' });
      onDone('Đã xác minh thông tin khôi phục. Chủ account giờ có thể gửi duyệt listing.');
      await load();
    } catch (err) {
      onError(err);
    } finally {
      setBusyId(null);
    }
  };

  return (
    <section id="verify-accounts" className="card stack">
      <h2 style={{ fontSize: '1.1rem', margin: 0 }}>
        Account chờ xác minh{result && result.totalElements > 0 ? ` (${result.totalElements})` : ''}
      </h2>
      <p className="muted" style={{ margin: 0 }}>
        Kiểm tra chủ account nắm giữ email, số điện thoại và 2FA khôi phục, rồi bấm “Xác minh”.
      </p>
      {accounts === null && <p className="muted">Đang tải…</p>}
      {accounts?.length === 0 && <p className="muted">Không có account nào chờ xác minh.</p>}
      {accounts?.map((a) => (
        <div key={a.id} className="row spread" style={{ borderTop: '1px solid var(--border, #e5e7eb)', paddingTop: '0.5rem' }}>
          <div>
            <strong>{games[a.gameId] ?? 'Game'}</strong>
            {a.serverRegion ? ` · ${a.serverRegion}` : ''} · {a.loginHint ?? '***'}
            {a.accountKey && <> · ID trong game: <strong>{a.accountKey}</strong></>}
            <div className="muted" style={{ fontSize: '0.9rem' }}>
              Gửi lúc {a.securityStateChangedAt ? formatDateTime(a.securityStateChangedAt) : '—'} ·{' '}
              chủ account <UserRef id={a.ownerId} />
            </div>
          </div>
          <button className="btn btn-primary" disabled={busyId === a.id} onClick={() => verify(a.id)}>
            {busyId === a.id ? 'Đang xác minh…' : 'Xác minh'}
          </button>
        </div>
      ))}
      <Pager result={result} page={page} onPage={setPage} />
    </section>
  );
}
