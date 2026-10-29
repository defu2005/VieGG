import { useEffect, useState, type KeyboardEvent, type MouseEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { api } from '../api/client';
import type { NotificationItem, PageResponse } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { Countdown } from '../components/Countdown';
import { Pager, pageAfterShrink } from '../components/Pager';
import { errorMessage, formatDateTime, formatVnd } from '../lib/format';
import { viewNotification, type NotificationDetail } from '../lib/notificationLinks';
import { notifyUnreadChanged } from '../lib/unreadNotifications';
import { nowMs } from '../lib/serverClock';

function DetailValue({ detail }: { detail: NotificationDetail }) {
  switch (detail.kind) {
    case 'money':
      return <>{formatVnd(Number(detail.value))}</>;
    case 'time':
      return <>{formatDateTime(detail.value)}</>;
    case 'countdown':
      return new Date(detail.value).getTime() > nowMs() ? (
        <Countdown until={detail.value} />
      ) : (
        <>{formatDateTime(detail.value)}</>
      );
    default:
      return <>{detail.value}</>;
  }
}

export function NotificationsPage() {
  const { me, can } = useAuth();
  const navigate = useNavigate();
  const [result, setResult] = useState<PageResponse<NotificationItem> | null>(null);
  const items = result?.content ?? null;
  const [page, setPage] = useState(0);
  const [unreadOnly, setUnreadOnly] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = (onlyUnread: boolean, p: number) => {
    setError(null);
    api<PageResponse<NotificationItem>>(`/notifications?page=${p}&size=30${onlyUnread ? '&unread=true' : ''}`)
      .then((r) => {
        const back = pageAfterShrink(r, p);
        if (back != null) setPage(back);
        else setResult(r);
      })
      .catch((err) => setError(errorMessage(err)));
  };

  useEffect(() => {
    load(unreadOnly, page);
  }, [unreadOnly, page]);

  /** Marks one notification read locally and on the server (fire-and-forget when navigating away). */
  const markRead = async (n: NotificationItem) => {
    if (n.readAt) return;
    setResult((r) =>
      r && { ...r, content: r.content.map((x) => (x.id === n.id ? { ...x, readAt: new Date().toISOString() } : x)) },
    );
    try {
      await api(`/notifications/${n.id}/read`, { method: 'POST' });
      notifyUnreadChanged();
    } catch (err) {
      setError(errorMessage(err));
    }
  };

  const markAllRead = async () => {
    try {
      await api('/notifications/read-all', { method: 'POST' });
      notifyUnreadChanged();
      load(unreadOnly, page);
    } catch (err) {
      setError(errorMessage(err));
    }
  };

  const hasUnread = items?.some((n) => !n.readAt) ?? false;

  return (
    <div className="stack">
      <div className="row spread">
        <h1>Thông báo</h1>
        <div className="row">
          {hasUnread && (
            <button className="btn" onClick={markAllRead}>
              Đánh dấu tất cả đã đọc
            </button>
          )}
          <label className="checkbox">
            <input type="checkbox" checked={unreadOnly} onChange={(e) => {
                setUnreadOnly(e.target.checked);
                setPage(0);
              }}
            />
            Chỉ hiện chưa đọc
          </label>
        </div>
      </div>

      {error && <p className="error">{error}</p>}
      {!items && !error && <div className="skeleton" aria-busy="true" />}
      {items && items.length === 0 && <p className="muted">Không có thông báo.</p>}

      {items?.map((n) => {
        const view = me ? viewNotification(n, { id: me.id, can }) : { primary: null, secondary: [], details: [] };
        const open = () => {
          void markRead(n);
          if (view.primary) navigate(view.primary.to);
        };
        const onKey = (e: KeyboardEvent) => {
          if (e.target === e.currentTarget && (e.key === 'Enter' || e.key === ' ')) {
            e.preventDefault();
            open();
          }
        };
        // Buttons inside the card handle their own click; don't also trigger the card.
        const stop = (e: MouseEvent) => e.stopPropagation();
        return (
          <div
            key={n.id}
            className={`card notification ${n.readAt ? 'is-read' : 'is-unread'} ${view.primary ? 'is-clickable' : ''}`}
            role={view.primary ? 'link' : undefined}
            tabIndex={view.primary ? 0 : undefined}
            onClick={view.primary ? open : undefined}
            onKeyDown={view.primary ? onKey : undefined}
          >
            <div className="row spread">
              <strong>
                {!n.readAt && <span className="notification-dot" aria-label="Chưa đọc" />}
                {n.title}
              </strong>
              <span className="muted">{formatDateTime(n.createdAt)}</span>
            </div>
            {n.body && n.body !== n.title && <p style={{ margin: '0.35rem 0' }}>{n.body}</p>}
            {view.details.length > 0 && (
              <dl className="notification-details">
                {view.details.map((dt) => (
                  <div key={dt.label}>
                    <dt>{dt.label}</dt>
                    <dd>
                      <DetailValue detail={dt} />
                    </dd>
                  </div>
                ))}
              </dl>
            )}
            <div className="row" onClick={stop}>
              {view.primary && (
                <Link className="btn btn-primary" to={view.primary.to} onClick={() => void markRead(n)}>
                  {view.primary.label}
                </Link>
              )}
              {view.secondary.map((a) => (
                <Link key={a.to} className="btn" to={a.to} onClick={() => void markRead(n)}>
                  {a.label}
                </Link>
              ))}
              {!n.readAt && (
                <button className="btn btn-ghost" onClick={() => void markRead(n)}>
                  Đánh dấu đã đọc
                </button>
              )}
            </div>
          </div>
        );
      })}
      <Pager result={result} page={page} onPage={setPage} />
    </div>
  );
}
