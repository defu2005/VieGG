import { useState } from 'react';
import { Link } from 'react-router-dom';
import type { Order, OrderStatus } from '../api/types';
import { Countdown } from '../components/Countdown';
import { PageHelp } from '../components/PageHelp';
import { Pager, usePaged } from '../components/Pager';
import { StatusBadge } from '../components/StatusBadge';
import { errorMessage, formatDateTime, formatVnd } from '../lib/format';

type Role = 'RENTER' | 'OWNER';

const FILTERS: { label: string; status: OrderStatus | '' }[] = [
  { label: 'Tất cả', status: '' },
  { label: 'Chờ thanh toán', status: 'PENDING_PAYMENT' },
  { label: 'Chờ duyệt', status: 'AWAITING_OWNER_APPROVAL' },
  { label: 'Đã xác nhận', status: 'CONFIRMED' },
  { label: 'Đang thuê', status: 'IN_RENTAL' },
  { label: 'Hoàn tất', status: 'ENDED' },
  { label: 'Đã hủy', status: 'CANCELLED' },
];

export function OrdersPage() {
  const [role, setRole] = useState<Role>('RENTER');
  const [status, setStatus] = useState<OrderStatus | ''>('');
  const [error, setError] = useState<string | null>(null);
  const query = new URLSearchParams({ role });
  if (status) query.set('status', status);
  const paged = usePaged<Order>(`/orders?${query}`, { onError: (err) => setError(errorMessage(err)) });
  const orders = paged.items;

  return (
    <div className="stack">
      <div className="row spread">
        <h1>Đơn của tôi</h1>
        <div className="row">
          <button className={`btn ${role === 'RENTER' ? 'btn-primary' : ''}`} onClick={() => setRole('RENTER')}>
            Tôi thuê
          </button>
          <button className={`btn ${role === 'OWNER' ? 'btn-primary' : ''}`} onClick={() => setRole('OWNER')}>
            Tôi cho thuê
          </button>
        </div>
      </div>

      <PageHelp id="orders" title="Theo dõi đơn thuê">
        <p>
          <strong>Tôi thuê</strong>: các đơn bạn đi thuê. Mở đơn để thanh toán, xem thông tin đăng nhập khi đến giờ, hoặc gửi khiếu nại.
        </p>
        <p>
          <strong>Tôi cho thuê</strong>: các đơn người khác thuê account của bạn. Đơn “Chờ chủ account duyệt” cần bạn duyệt trước hạn.
        </p>
        <p className="muted">Rê chuột lên nhãn trạng thái để xem giải thích.</p>
      </PageHelp>

      <div className="chips" role="group" aria-label="Lọc theo trạng thái">
        {FILTERS.map((f) => (
          <button key={f.label} className={`chip ${status === f.status ? 'active' : ''}`} onClick={() => setStatus(f.status)}>
            {f.label}
          </button>
        ))}
      </div>

      {error && <p className="error">{error}</p>}
      {!orders && !error && <div className="skeleton" aria-busy="true" />}

      {orders && orders.length === 0 && (
        <div className="card">
          <p className="muted">Chưa có đơn nào{status ? ' ở trạng thái này' : ''}.</p>
          {role === 'RENTER' && !status && (
            <Link to="/listings" className="btn btn-primary">
              Tìm account để thuê
            </Link>
          )}
        </div>
      )}

      {orders && orders.length > 0 && (
        <div className="card" style={{ overflowX: 'auto' }}>
          <table>
            <thead>
              <tr>
                <th>Mã đơn</th>
                <th>Bắt đầu</th>
                <th>Kết thúc</th>
                <th>Tổng thanh toán</th>
                <th>Trạng thái</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {orders.map((o) => (
                <tr key={o.id}>
                  <td>
                    <Link to={`/orders/${o.id}`}>{o.id.slice(0, 8)}</Link>
                  </td>
                  <td>{formatDateTime(o.startAt)}</td>
                  <td>{formatDateTime(o.scheduledEndAt)}</td>
                  <td>{formatVnd(o.amountToPay)}</td>
                  <td>
                    <StatusBadge status={o.status} />
                    {o.status === 'PENDING_PAYMENT' && o.paymentExpiresAt && role === 'RENTER' && (
                      <div className="muted">
                        Còn <Countdown until={o.paymentExpiresAt} />
                      </div>
                    )}
                  </td>
                  <td>
                    {role === 'RENTER' && (o.status === 'ENDED' || o.status === 'CANCELLED' || o.status === 'EXPIRED' || o.status === 'REJECTED') && (
                      <Link to={`/listings/${o.listingId}`}>Thuê lại</Link>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <Pager result={paged.result} page={paged.page} onPage={paged.setPage} />
    </div>
  );
}
