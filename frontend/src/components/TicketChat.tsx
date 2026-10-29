import { useCallback, useEffect, useRef, useState, type FormEvent, type KeyboardEvent } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import { errorMessage, formatDateTime } from '../lib/format';
import {
  notifyHelpdeskChanged,
  PARTICIPANT_ROLE,
  TICKET_CATEGORY,
  TICKET_STATUS,
  type Ticket,
  type TicketMessage,
  type TicketStatus,
} from '../lib/helpdesk';
import { compressImage } from '../lib/image';
import { labelOf } from '../lib/labels';
import { FlashMessages, useFlash } from '../lib/useFlash';
import { StatusBadge } from './StatusBadge';
import { UserSearch } from './UserSearch';

const POLL_MS = 5_000;
const MAX_IMAGE_BYTES = 5 * 1024 * 1024;
const SERVER_IMAGE_TYPES = ['image/jpeg', 'image/png', 'image/webp'];

/** Loads a chat image with the bearer token (img src cannot send it). */
function ChatImage({ ticketId, attachmentId }: { ticketId: string; attachmentId: string }) {
  const [url, setUrl] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    let objectUrl: string | null = null;
    let cancelled = false;
    api<Blob>(`/tickets/${ticketId}/attachments/${attachmentId}`, { responseType: 'blob' })
      .then((b) => {
        if (cancelled) return;
        objectUrl = URL.createObjectURL(b);
        setUrl(objectUrl);
      })
      .catch(() => !cancelled && setFailed(true));
    return () => {
      cancelled = true;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [ticketId, attachmentId]);
  if (failed) return <span className="muted">Không tải được ảnh.</span>;
  if (!url) return <span className="chat-image img-placeholder" aria-label="Đang tải ảnh…" />;
  return (
    <a href={url} target="_blank" rel="noreferrer noopener" className="chat-image">
      <img src={url} alt="Ảnh đính kèm" />
    </a>
  );
}

/** One conversation: messages (polled), composer, and — for staff — invite / assign / status tools. */
export function TicketChat({ ticketId, onChanged }: { ticketId: string; onChanged?: () => void }) {
  const { me } = useAuth();
  const [ticket, setTicket] = useState<Ticket | null>(null);
  const [messages, setMessages] = useState<TicketMessage[] | null>(null);
  const [text, setText] = useState('');
  const [image, setImage] = useState<File | null>(null);
  const [showInvite, setShowInvite] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const flash = useFlash();
  const listRef = useRef<HTMLDivElement>(null);
  const fileRef = useRef<HTMLInputElement>(null);
  const lastCount = useRef(0);
  const onChangedRef = useRef(onChanged);
  onChangedRef.current = onChanged;

  const load = useCallback(async () => {
    try {
      const [t, m] = await Promise.all([
        api<Ticket>(`/tickets/${ticketId}`),
        api<TicketMessage[]>(`/tickets/${ticketId}/messages`),
      ]);
      setTicket(t);
      setMessages(m);
      setLoadError(null);
      if (t.unread > 0) {
        await api(`/tickets/${ticketId}/read`, { method: 'POST' }).catch(() => undefined);
        notifyHelpdeskChanged();
        onChangedRef.current?.(); // drop the unread badge in the ticket list
      }
    } catch (err) {
      setLoadError(errorMessage(err));
    }
  }, [ticketId]);

  useEffect(() => {
    setTicket(null);
    setMessages(null);
    lastCount.current = 0;
    load();
    const timer = setInterval(() => {
      if (document.visibilityState === 'visible') load();
    }, POLL_MS);
    return () => clearInterval(timer);
  }, [load]);

  // Keep the newest message in view when new ones arrive.
  useEffect(() => {
    const n = messages?.length ?? 0;
    if (n !== lastCount.current && listRef.current) {
      listRef.current.scrollTop = listRef.current.scrollHeight;
    }
    lastCount.current = n;
  }, [messages]);

  const afterChange = async () => {
    await load();
    onChanged?.();
    notifyHelpdeskChanged();
  };

  const send = async (e?: FormEvent) => {
    e?.preventDefault();
    const body = text.trim();
    if (!body && !image) return;
    const ok = await flash.run(async () => {
      if (image) {
        const blob = SERVER_IMAGE_TYPES.includes(image.type) && image.size <= MAX_IMAGE_BYTES ? image : await compressImage(image);
        const form = new FormData();
        form.append('file', blob, image.name);
        if (body) form.append('body', body);
        await api(`/tickets/${ticketId}/messages`, { method: 'POST', body: form });
      } else {
        await api(`/tickets/${ticketId}/messages`, { method: 'POST', body: { body } });
      }
    });
    if (ok) {
      setText('');
      setImage(null);
      if (fileRef.current) fileRef.current.value = '';
      await afterChange();
    }
  };

  const onKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault();
      send();
    }
  };

  const setStatus = async (status: TicketStatus) => {
    if (await flash.run(() => api(`/tickets/${ticketId}/status`, { method: 'POST', body: { status } }))) await afterChange();
  };
  const invite = async (userId: string) => {
    const ok = await flash.run(
      () => api(`/support/tickets/${ticketId}/participants`, { method: 'POST', body: { userId } }),
      'Đã mời vào cuộc trò chuyện.',
    );
    if (ok) {
      setShowInvite(false);
      await afterChange();
    }
  };
  const remove = async (userId: string, name: string) => {
    if (!window.confirm(`Mời ${name} ra khỏi cuộc trò chuyện?`)) return;
    if (await flash.run(() => api(`/support/tickets/${ticketId}/participants/${userId}`, { method: 'DELETE' }))) await afterChange();
  };
  const assign = async (assigneeId: string | null) => {
    if (await flash.run(() => api(`/support/tickets/${ticketId}/assign`, { method: 'POST', body: { assigneeId } }))) await afterChange();
  };

  if (loadError && !ticket) return <p className="error">{loadError}</p>;
  if (!ticket || !messages) return <p className="muted">Đang tải cuộc trò chuyện…</p>;

  const closed = ticket.status === 'CLOSED';
  const active = ticket.participants.filter((p) => p.active);
  const activeIds = new Set(active.map((p) => p.userId));
  const parties = ticket.orderParties;

  return (
    <div className="chat">
      <header className="chat-head stack">
        <div className="row spread">
          <h2 style={{ margin: 0 }}>{ticket.subject}</h2>
          <StatusBadge status={ticket.status} dict={TICKET_STATUS} />
        </div>
        <div className="row muted">
          <span className="badge">{labelOf(TICKET_CATEGORY, ticket.category)}</span>
          {ticket.orderId && (
            <span>
              Đơn <Link to={`/orders/${ticket.orderId}`}>{ticket.orderId.slice(0, 8)}</Link>
            </span>
          )}
          {ticket.disputeId && ticket.canManage && <Link to={`/support/disputes?q=${ticket.disputeId}`}>Xem khiếu nại</Link>}
          <span>Mở lúc {formatDateTime(ticket.createdAt)}</span>
          {ticket.assigneeName && <span>· Phụ trách: {ticket.assigneeName}</span>}
        </div>
        <div className="row chat-people" aria-label="Người trong cuộc trò chuyện">
          {active.map((p) => (
            <span key={p.userId} className={`chip${p.role === 'STAFF' ? ' chip-staff' : ''}`} title={PARTICIPANT_ROLE[p.role]}>
              {p.userId === me?.id ? 'Bạn' : p.displayName}
              <small> · {PARTICIPANT_ROLE[p.role]}</small>
              {ticket.canManage && p.role === 'PARTY' && (
                <button type="button" className="chip-x" aria-label={`Mời ${p.displayName} ra`} onClick={() => remove(p.userId, p.displayName)}>
                  ×
                </button>
              )}
            </span>
          ))}
        </div>

        {ticket.canManage ? (
          <div className="row chat-tools">
            {ticket.assigneeId !== me?.id && (
              <button className="btn btn-sm" disabled={flash.busy} onClick={() => assign(me?.id ?? null)}>
                Nhận xử lý
              </button>
            )}
            {parties && !activeIds.has(parties.renterId) && (
              <button className="btn btn-sm" disabled={flash.busy || closed} onClick={() => invite(parties.renterId)}>
                + Mời người thuê ({parties.renterName})
              </button>
            )}
            {parties && !activeIds.has(parties.ownerId) && (
              <button className="btn btn-sm" disabled={flash.busy || closed} onClick={() => invite(parties.ownerId)}>
                + Mời chủ account ({parties.ownerName})
              </button>
            )}
            <button className="btn btn-sm btn-ghost" disabled={closed} onClick={() => setShowInvite((v) => !v)}>
              {showInvite ? 'Đóng tìm người' : '+ Mời người khác'}
            </button>
            <select
              aria-label="Trạng thái"
              value={ticket.status}
              disabled={flash.busy}
              onChange={(e) => setStatus(e.target.value as TicketStatus)}
              style={{ width: 'auto' }}
            >
              {(Object.keys(TICKET_STATUS) as TicketStatus[]).map((s) => (
                <option key={s} value={s}>
                  {labelOf(TICKET_STATUS, s)}
                </option>
              ))}
            </select>
          </div>
        ) : (
          ticket.myRole && (
            <div className="row chat-tools">
              {ticket.status === 'RESOLVED' ? (
                <button className="btn btn-sm" disabled={flash.busy} onClick={() => setStatus('OPEN')}>
                  Mở lại yêu cầu
                </button>
              ) : (
                !closed && (
                  <button className="btn btn-sm btn-ghost" disabled={flash.busy} onClick={() => setStatus('RESOLVED')}>
                    ✓ Vấn đề đã được giải quyết
                  </button>
                )
              )}
            </div>
          )
        )}
        {showInvite && (
          <div className="card" style={{ marginBottom: 0 }}>
            <UserSearch onPick={(u) => invite(u.id)} pickLabel="Mời" excludeIds={[...activeIds]} />
          </div>
        )}
      </header>

      <div className="chat-list" ref={listRef} aria-live="polite">
        {messages.length === 0 && <p className="muted">Chưa có tin nhắn.</p>}
        {messages.map((m) =>
          m.kind === 'SYSTEM' ? (
            <div key={m.id} className="chat-system">
              {m.body} · {formatDateTime(m.createdAt)}
            </div>
          ) : (
            <div key={m.id} className={`chat-msg${m.senderId === me?.id ? ' mine' : ''}${m.senderRole === 'STAFF' ? ' staff' : ''}`}>
              <div className="chat-meta">
                {m.senderId === me?.id ? 'Bạn' : m.senderName}
                {m.senderRole === 'STAFF' && <span className="badge staff-badge">Hỗ trợ viên</span>}
                <span>{formatDateTime(m.createdAt)}</span>
              </div>
              <div className="chat-bubble">
                {m.body && <p>{m.body}</p>}
                {m.attachmentId && <ChatImage ticketId={ticketId} attachmentId={m.attachmentId} />}
              </div>
            </div>
          ),
        )}
      </div>

      <FlashMessages error={flash.error} notice={flash.notice} />
      {closed ? (
        <p className="muted chat-closed">
          Yêu cầu đã đóng.{' '}
          {ticket.canManage ? 'Đổi trạng thái để mở lại.' : <Link to="/tickets?new=1">Tạo yêu cầu mới</Link>}
        </p>
      ) : (
        <form className="chat-compose" onSubmit={send}>
          <textarea
            aria-label="Tin nhắn"
            placeholder="Nhập tin nhắn… (Enter để gửi, Shift+Enter xuống dòng)"
            rows={2}
            maxLength={4000}
            value={text}
            onChange={(e) => setText(e.target.value)}
            onKeyDown={onKeyDown}
          />
          <div className="row spread">
            <label className="btn btn-sm btn-ghost" style={{ cursor: 'pointer' }}>
              📎 {image ? image.name : 'Đính kèm ảnh'}
              <input
                ref={fileRef}
                type="file"
                accept="image/*"
                hidden
                onChange={(e) => setImage(e.target.files?.[0] ?? null)}
              />
            </label>
            <button className="btn btn-primary" disabled={flash.busy || (!text.trim() && !image)}>
              {flash.busy ? 'Đang gửi…' : 'Gửi'}
            </button>
          </div>
        </form>
      )}
    </div>
  );
}
