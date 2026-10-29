import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { api } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import { errorMessage, formatDateTime } from '../lib/format';
import { TICKET_CATEGORY, TICKET_STATUS, type Ticket } from '../lib/helpdesk';
import { labelOf } from '../lib/labels';
import { StatusBadge } from './StatusBadge';

/** Help desk conversations about this order, and a shortcut to start one (users) or a mediation (staff). */
export function OrderSupportSection({ orderId }: { orderId: string }) {
  const { can } = useAuth();
  const staff = can('helpdesk:handle');
  const navigate = useNavigate();
  const [tickets, setTickets] = useState<Ticket[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    api<Ticket[]>(`/orders/${orderId}/tickets`)
      .then(setTickets)
      .catch(() => setTickets([]));
  }, [orderId]);

  const startMediation = async () => {
    setBusy(true);
    setError(null);
    try {
      const t = await api<Ticket>('/support/tickets', {
        method: 'POST',
        body: {
          category: 'MEDIATION',
          orderId,
          subject: `Đàm phán đơn ${orderId.slice(0, 8)}`,
          body: 'Chào hai bạn, mình là hỗ trợ viên. Mình mở cuộc trò chuyện này để hai bên cùng trao đổi và thống nhất cách giải quyết.',
        },
      });
      navigate(`/tickets/${t.id}`);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <section id="support" className="card stack">
      <div className="row spread">
        <h3 style={{ margin: 0 }}>Hỗ trợ cho đơn này</h3>
        {staff ? (
          <button className="btn btn-sm" disabled={busy} onClick={startMediation} title="Mời cả người thuê và chủ account vào một cuộc trò chuyện">
            Mở phòng đàm phán
          </button>
        ) : (
          <Link className="btn btn-sm" to={`/tickets?new=1&orderId=${orderId}`}>
            💬 Chat với hỗ trợ về đơn này
          </Link>
        )}
      </div>
      {error && <p className="error">{error}</p>}
      {tickets && tickets.length === 0 && <p className="muted">Chưa có cuộc trò chuyện hỗ trợ nào về đơn này.</p>}
      {tickets?.map((t) => (
        <Link key={t.id} to={`/tickets/${t.id}`} className="ticket-item">
          <div className="row spread">
            <strong className="ticket-subject">{t.subject}</strong>
            <StatusBadge status={t.status} dict={TICKET_STATUS} />
          </div>
          <div className="muted" style={{ fontSize: '0.8rem' }}>
            {labelOf(TICKET_CATEGORY, t.category)} · {formatDateTime(t.lastMessageAt)}
            {t.unread > 0 ? ` · ${t.unread} tin mới` : ''}
          </div>
        </Link>
      ))}
    </section>
  );
}
