import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { api } from '../api/client';
import type { Order, PageResponse, UserHit } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { PageHelp } from '../components/PageHelp';
import { Pager } from '../components/Pager';
import { StatusBadge } from '../components/StatusBadge';
import { TicketChat } from '../components/TicketChat';
import { UserSearch } from '../components/UserSearch';
import { errorMessage, formatDateTime } from '../lib/format';
import { notifyHelpdeskChanged, TICKET_CATEGORY, TICKET_STATUS, type Ticket, type TicketCategory, type TicketStatus } from '../lib/helpdesk';
import { labelOf, ORDER_STATUS } from '../lib/labels';
import { FlashMessages, useFlash } from '../lib/useFlash';

type QueueFilter = 'ACTIVE' | TicketStatus | 'ALL';
const QUEUE_FILTERS: { value: QueueFilter; label: string }[] = [
  { value: 'ACTIVE', label: 'Đang mở' },
  { value: 'OPEN', label: 'Chờ hỗ trợ' },
  { value: 'WAITING_USER', label: 'Đã trả lời' },
  { value: 'RESOLVED', label: 'Đã giải quyết' },
  { value: 'CLOSED', label: 'Đã đóng' },
  { value: 'ALL', label: 'Tất cả' },
];

/**
 * Help desk. Users: their conversations with support + a form to ask for help. Staff (helpdesk:handle): the
 * ticket queue, and conversations they start themselves (e.g. a mediation between renter and owner).
 */
export function TicketsPage() {
  const { id } = useParams();
  const [params, setParams] = useSearchParams();
  const navigate = useNavigate();
  const { can } = useAuth();
  const staff = can('helpdesk:handle');
  const creating = params.get('new') === '1' || params.has('orderId');

  const [result, setResult] = useState<PageResponse<Ticket> | null>(null);
  const tickets = result?.content ?? null;
  const [page, setPage] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [filter, setFilter] = useState<QueueFilter>('ACTIVE');
  const [category, setCategory] = useState<TicketCategory | ''>('');
  const [mine, setMine] = useState(false);
  const [unassigned, setUnassigned] = useState(false);
  const [qInput, setQInput] = useState('');
  const [q, setQ] = useState('');

  let listPath = '/tickets?';
  if (staff) {
    const query = new URLSearchParams();
    if (filter === 'ACTIVE') ['OPEN', 'WAITING_USER'].forEach((s) => query.append('status', s));
    else if (filter === 'ALL') (Object.keys(TICKET_STATUS) as TicketStatus[]).forEach((s) => query.append('status', s));
    else query.append('status', filter);
    if (category) query.set('category', category);
    if (mine) query.set('mine', 'true');
    if (unassigned) query.set('unassigned', 'true');
    if (q) query.set('q', q);
    listPath = `/support/tickets?${query}&`;
  }

  // A new filter starts again from the first page.
  useEffect(() => setPage(0), [listPath]);

  const loadList = useCallback(() => {
    return api<PageResponse<Ticket>>(`${listPath}page=${page}&size=30`)
      .then((r) => {
        if (r.content.length === 0 && page > 0 && r.totalPages > 0) {
          setPage(r.totalPages - 1);
          return;
        }
        setResult(r);
        setError(null);
      })
      .catch((err) => setError(errorMessage(err)));
  }, [listPath, page]);

  useEffect(() => {
    loadList();
    const timer = setInterval(() => document.visibilityState === 'visible' && loadList(), 15_000);
    return () => clearInterval(timer);
  }, [loadList]);

  const openNew = () => setParams({ new: '1' });
  const closeNew = () => setParams({});

  return (
    <div className="stack">
      <div className="row spread">
        <h1>{staff ? 'Hỗ trợ khách hàng' : 'Hỗ trợ'}</h1>
        <button className="btn btn-primary" onClick={openNew}>
          {staff ? '+ Tạo cuộc trò chuyện' : '+ Tạo yêu cầu hỗ trợ'}
        </button>
      </div>
      <PageHelp id={staff ? 'helpdesk-staff' : 'helpdesk'} title={staff ? 'Xử lý yêu cầu hỗ trợ' : 'Chat với hỗ trợ viên'}>
        {staff ? (
          <ul>
            <li>“Chờ hỗ trợ” là yêu cầu người dùng vừa nhắn và chưa ai trả lời. Trả lời là bạn tự nhận xử lý.</li>
            <li>
              Với yêu cầu gắn đơn thuê, dùng <strong>Mời người thuê / Mời chủ account</strong> để đưa bên kia vào đàm phán.
              Người được mời chỉ thấy tin nhắn từ lúc họ vào.
            </li>
            <li>Từ trang Khiếu nại có thể mở ngay “Phòng đàm phán” với cả hai bên.</li>
          </ul>
        ) : (
          <ul>
            <li>Hỏi đáp, nhờ hỗ trợ hoặc khiếu nại: tạo yêu cầu và trò chuyện trực tiếp với hỗ trợ viên.</li>
            <li>Nếu liên quan đến một đơn thuê, hãy chọn đơn đó để hỗ trợ viên xem nhanh.</li>
            <li>Khi cần, hỗ trợ viên có thể mời bên còn lại của đơn vào để cùng thương lượng.</li>
            <li>Khiếu nại cần khấu trừ cọc hoặc hoàn tiền vẫn mở ở mục “Khiếu nại” trong trang đơn thuê.</li>
          </ul>
        )}
      </PageHelp>

      {staff && (
        <div className="row">
          <div className="tabs row" role="tablist" aria-label="Trạng thái">
            {QUEUE_FILTERS.map((f) => (
              <button
                key={f.value}
                role="tab"
                aria-selected={filter === f.value}
                className={`btn btn-sm${filter === f.value ? ' btn-primary' : ' btn-ghost'}`}
                onClick={() => setFilter(f.value)}
              >
                {f.label}
              </button>
            ))}
          </div>
          <select aria-label="Loại" value={category} onChange={(e) => setCategory(e.target.value as TicketCategory | '')} style={{ width: 'auto' }}>
            <option value="">Mọi loại</option>
            {(Object.keys(TICKET_CATEGORY) as TicketCategory[]).map((c) => (
              <option key={c} value={c}>
                {labelOf(TICKET_CATEGORY, c)}
              </option>
            ))}
          </select>
          <label className="row" style={{ gap: '0.3rem' }}>
            <input type="checkbox" checked={mine} onChange={(e) => setMine(e.target.checked)} /> Của tôi
          </label>
          <label className="row" style={{ gap: '0.3rem' }}>
            <input type="checkbox" checked={unassigned} onChange={(e) => setUnassigned(e.target.checked)} /> Chưa ai nhận
          </label>
          <form
            className="row"
            role="search"
            onSubmit={(e) => {
              e.preventDefault();
              setQ(qInput.trim());
            }}
          >
            <input type="search" placeholder="Tìm theo tiêu đề, nội dung" aria-label="Tìm yêu cầu" value={qInput} onChange={(e) => setQInput(e.target.value)} />
            <button className="btn btn-sm">Tìm</button>
          </form>
        </div>
      )}

      {error && <p className="error">{error}</p>}
      <div className="helpdesk">
        <aside className="helpdesk-list" aria-label="Danh sách yêu cầu">
          {tickets === null && <p className="muted">Đang tải…</p>}
          {tickets?.length === 0 && (
            <p className="muted">{staff ? 'Không có yêu cầu nào.' : 'Bạn chưa có yêu cầu hỗ trợ nào.'}</p>
          )}
          {tickets?.map((t) => (
            <Link key={t.id} to={`/tickets/${t.id}`} className={`ticket-item${t.id === id ? ' active' : ''}`}>
              <div className="row spread">
                <strong className="ticket-subject">{t.subject}</strong>
                {t.unread > 0 && <span className="nav-badge">{t.unread}</span>}
              </div>
              <div className="ticket-preview muted">{t.lastMessagePreview}</div>
              <div className="row spread muted" style={{ fontSize: '0.8rem' }}>
                <span>
                  {labelOf(TICKET_CATEGORY, t.category)}
                  {staff && t.createdByName ? ` · ${t.createdByName}` : ''}
                </span>
                <StatusBadge status={t.status} dict={TICKET_STATUS} />
              </div>
              <div className="muted" style={{ fontSize: '0.75rem' }}>
                {formatDateTime(t.lastMessageAt)}
                {staff && (t.assigneeName ? ` · ${t.assigneeName}` : ' · chưa ai nhận')}
              </div>
            </Link>
          ))}
          <Pager result={result} page={page} onPage={setPage} />
        </aside>
        <section className="helpdesk-main card">
          {creating ? (
            staff ? (
              <StaffNewTicket
                initialOrderId={params.get('orderId') ?? ''}
                onCancel={closeNew}
                onCreated={(t) => {
                  loadList();
                  navigate(`/tickets/${t.id}`);
                }}
              />
            ) : (
              <NewTicketForm
                initialOrderId={params.get('orderId') ?? ''}
                onCancel={closeNew}
                onCreated={(t) => {
                  loadList();
                  notifyHelpdeskChanged();
                  navigate(`/tickets/${t.id}`);
                }}
              />
            )
          ) : id ? (
            <TicketChat key={id} ticketId={id} onChanged={loadList} />
          ) : (
            <div className="stack" style={{ textAlign: 'center', padding: '2rem 1rem' }}>
              <p style={{ fontSize: '2rem', margin: 0 }} aria-hidden="true">
                💬
              </p>
              <p className="muted">Chọn một cuộc trò chuyện bên trái{staff ? '' : ', hoặc tạo yêu cầu mới để chat với hỗ trợ viên'}.</p>
              {!staff && (
                <div>
                  <button className="btn btn-primary" onClick={openNew}>
                    + Tạo yêu cầu hỗ trợ
                  </button>
                </div>
              )}
            </div>
          )}
        </section>
      </div>
    </div>
  );
}

/** Recent orders the user rents or owns, for the "about which order" picker. */
function useMyOrders(): Order[] {
  const [orders, setOrders] = useState<Order[]>([]);
  useEffect(() => {
    Promise.all(
      ['RENTER', 'OWNER'].map((role) =>
        api<PageResponse<Order>>(`/orders?role=${role}&size=20`)
          .then((p) => p.content)
          .catch(() => [] as Order[]),
      ),
    ).then(([a, b]) => {
      const all = [...a, ...b].sort((x, y) => y.createdAt.localeCompare(x.createdAt));
      setOrders(all.filter((o, i) => all.findIndex((z) => z.id === o.id) === i));
    });
  }, []);
  return orders;
}

function NewTicketForm({
  initialOrderId,
  onCancel,
  onCreated,
}: {
  initialOrderId: string;
  onCancel: () => void;
  onCreated: (t: Ticket) => void;
}) {
  const orders = useMyOrders();
  const [category, setCategory] = useState<TicketCategory>(initialOrderId ? 'COMPLAINT' : 'QUESTION');
  const [orderId, setOrderId] = useState(initialOrderId);
  const [subject, setSubject] = useState('');
  const [body, setBody] = useState('');
  const flash = useFlash();

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    let created: Ticket | null = null;
    const ok = await flash.run(async () => {
      created = await api<Ticket>('/tickets', {
        method: 'POST',
        body: { category, subject: subject.trim(), body: body.trim(), orderId: orderId || undefined },
      });
    });
    if (ok && created) onCreated(created);
  };

  return (
    <form className="stack" onSubmit={submit}>
      <h2 style={{ margin: 0 }}>Tạo yêu cầu hỗ trợ</h2>
      <div className="field">
        <label>Bạn cần gì?</label>
        <div className="row">
          {(['QUESTION', 'SUPPORT', 'COMPLAINT'] as TicketCategory[]).map((c) => (
            <button
              key={c}
              type="button"
              className={`btn btn-sm${category === c ? ' btn-primary' : ''}`}
              aria-pressed={category === c}
              title={TICKET_CATEGORY[c].hint}
              onClick={() => setCategory(c)}
            >
              {labelOf(TICKET_CATEGORY, c)}
            </button>
          ))}
        </div>
        <span className="field-hint">{TICKET_CATEGORY[category].hint}</span>
      </div>
      <div className="field">
        <label htmlFor="ticket-order">Liên quan đến đơn thuê (không bắt buộc)</label>
        <select id="ticket-order" value={orderId} onChange={(e) => setOrderId(e.target.value)}>
          <option value="">— Không liên quan đơn nào —</option>
          {orderId && !orders.some((o) => o.id === orderId) && <option value={orderId}>Đơn {orderId.slice(0, 8)}</option>}
          {orders.map((o) => (
            <option key={o.id} value={o.id}>
              Đơn {o.id.slice(0, 8)} · {labelOf(ORDER_STATUS, o.status)} · {formatDateTime(o.startAt)}
            </option>
          ))}
        </select>
      </div>
      <div className="field">
        <label htmlFor="ticket-subject">Tiêu đề</label>
        <input id="ticket-subject" required maxLength={200} value={subject} onChange={(e) => setSubject(e.target.value)} placeholder="Ví dụ: Không đăng nhập được account đã thuê" />
      </div>
      <div className="field">
        <label htmlFor="ticket-body">Nội dung</label>
        <textarea id="ticket-body" required rows={5} maxLength={4000} value={body} onChange={(e) => setBody(e.target.value)} placeholder="Mô tả chi tiết vấn đề. Có thể gửi thêm ảnh sau khi tạo." />
      </div>
      <FlashMessages error={flash.error} notice={flash.notice} />
      <div className="row">
        <button className="btn btn-primary" disabled={flash.busy || !subject.trim() || !body.trim()}>
          {flash.busy ? 'Đang gửi…' : 'Gửi yêu cầu'}
        </button>
        <button type="button" className="btn btn-ghost" onClick={onCancel}>
          Huỷ
        </button>
      </div>
    </form>
  );
}

function StaffNewTicket({
  initialOrderId,
  onCancel,
  onCreated,
}: {
  initialOrderId: string;
  onCancel: () => void;
  onCreated: (t: Ticket) => void;
}) {
  const [category, setCategory] = useState<TicketCategory>('MEDIATION');
  const [orderId, setOrderId] = useState(initialOrderId);
  const [people, setPeople] = useState<UserHit[]>([]);
  const [subject, setSubject] = useState(initialOrderId ? `Đàm phán đơn ${initialOrderId.slice(0, 8)}` : '');
  const [body, setBody] = useState('');
  const flash = useFlash();

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    let created: Ticket | null = null;
    const ok = await flash.run(async () => {
      created = await api<Ticket>('/support/tickets', {
        method: 'POST',
        body: {
          category,
          subject: subject.trim(),
          body: body.trim(),
          orderId: orderId.trim() || undefined,
          userIds: people.map((p) => p.id),
        },
      });
    });
    if (ok && created) onCreated(created);
  };

  return (
    <form className="stack" onSubmit={submit}>
      <h2 style={{ margin: 0 }}>Tạo cuộc trò chuyện với người dùng</h2>
      <div className="field">
        <label htmlFor="st-category">Loại</label>
        <select id="st-category" value={category} onChange={(e) => setCategory(e.target.value as TicketCategory)}>
          {(Object.keys(TICKET_CATEGORY) as TicketCategory[]).map((c) => (
            <option key={c} value={c}>
              {labelOf(TICKET_CATEGORY, c)}
            </option>
          ))}
        </select>
      </div>
      <div className="field">
        <label htmlFor="st-order">Mã đơn thuê (không bắt buộc)</label>
        <input id="st-order" value={orderId} onChange={(e) => setOrderId(e.target.value)} placeholder="Mã đơn đầy đủ" />
        <span className="field-hint">Có mã đơn mà không chọn người: tự mời cả người thuê và chủ account.</span>
      </div>
      <div className="field">
        <label>Người tham gia</label>
        <div className="row">
          {people.map((p) => (
            <span key={p.id} className="chip">
              {p.displayName} <small>{p.email}</small>
              <button type="button" className="chip-x" aria-label={`Bỏ ${p.displayName}`} onClick={() => setPeople((x) => x.filter((y) => y.id !== p.id))}>
                ×
              </button>
            </span>
          ))}
          {people.length === 0 && <span className="muted">Chưa chọn ai.</span>}
        </div>
        <UserSearch pickLabel="Thêm" excludeIds={people.map((p) => p.id)} onPick={(u) => setPeople((x) => [...x, u])} />
      </div>
      <div className="field">
        <label htmlFor="st-subject">Tiêu đề</label>
        <input id="st-subject" required maxLength={200} value={subject} onChange={(e) => setSubject(e.target.value)} />
      </div>
      <div className="field">
        <label htmlFor="st-body">Tin nhắn đầu tiên</label>
        <textarea id="st-body" required rows={4} maxLength={4000} value={body} onChange={(e) => setBody(e.target.value)} />
      </div>
      <FlashMessages error={flash.error} notice={flash.notice} />
      <div className="row">
        <button className="btn btn-primary" disabled={flash.busy || !subject.trim() || !body.trim() || (people.length === 0 && !orderId.trim())}>
          Tạo và gửi
        </button>
        <button type="button" className="btn btn-ghost" onClick={onCancel}>
          Huỷ
        </button>
      </div>
    </form>
  );
}
