import { useEffect, useState, type FormEvent } from 'react';
import { useSearchParams } from 'react-router-dom';
import { api } from '../api/client';
import type { LedgerOrderSummary, LedgerTx, Withdrawal } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { errorMessage, formatDateTime, formatVnd } from '../lib/format';
import { ACCOUNT_CODE, labelOf, TX_TYPE } from '../lib/labels';
import { CopyId } from '../components/CopyId';
import { DataTable } from '../components/DataView';
import { StatusBadge } from '../components/StatusBadge';
import { UserRef } from '../components/UserRef';
import { UserSearch } from '../components/UserSearch';
import { PageHelp } from '../components/PageHelp';
import { Pager, usePaged } from '../components/Pager';
import { FlashMessages, useFlash } from '../lib/useFlash';

type WithdrawalStatus = 'REQUESTED' | 'PAID' | 'FAILED' | '';

export function FinancePage() {
  const { can } = useAuth();
  const canPayout = can('withdrawal:mark_paid');
  const [params, setParams] = useSearchParams();
  const ownerId = params.get('ownerId');
  const [status, setStatus] = useState<WithdrawalStatus>(ownerId ? '' : 'REQUESTED');
  const [picking, setPicking] = useState(false);
  const flash = useFlash();
  const paged = usePaged<Withdrawal>(`/finance/withdrawals?${withdrawalQuery(status, ownerId)}`, {
    onError: (err) => flash.setError(errorMessage(err)),
  });
  const items = paged.items;
  const load = paged.reload;

  const setOwner = (id: string | null) => {
    const next = new URLSearchParams(params);
    if (id) next.set('ownerId', id);
    else next.delete('ownerId');
    setParams(next);
    setPicking(false);
  };

  const markPaid = async (w: Withdrawal) => {
    const bankRef = window.prompt('Mã giao dịch ngân hàng (in trên biên lai chuyển khoản):');
    if (bankRef === null) return; // user cancelled
    if (!bankRef.trim()) {
      flash.setNotice(null);
      flash.setError('Chưa đánh dấu đã chi: cần nhập mã giao dịch ngân hàng.');
      return;
    }
    const ok = await flash.run(
      () => api(`/finance/withdrawals/${w.id}/mark-paid`, { method: 'POST', body: { bankRef: bankRef.trim() } }),
      'Đã đánh dấu đã chi.',
    );
    if (ok) load();
  };

  const markFailed = async (w: Withdrawal) => {
    const reason = window.prompt('Lý do chuyển khoản thất bại:');
    if (reason === null) return; // user cancelled
    if (!reason.trim()) {
      flash.setNotice(null);
      flash.setError('Chưa đánh dấu thất bại: cần nhập lý do.');
      return;
    }
    const ok = await flash.run(
      () => api(`/finance/withdrawals/${w.id}/mark-failed`, { method: 'POST', body: { reason: reason.trim() } }),
      'Đã đánh dấu thất bại và hoàn lại ví.',
    );
    if (ok) load();
  };

  return (
    <div className="stack">
      <h1>Tài chính</h1>
      <PageHelp id="finance" title="Chi trả và đối soát">
        <ul>
          <li>
            <strong>Yêu cầu rút tiền</strong>: cột “Người rút” cho biết ai yêu cầu; “Lọc theo người dùng” để xem lệnh của một người. Chuyển khoản cho chủ account, rồi bấm “Đã chi” và nhập mã giao dịch ngân hàng. Nếu chuyển lỗi, bấm “Thất bại”, tiền sẽ tự trả lại vào ví của họ.
          </li>
          <li>
            <strong>Đối soát sổ cái</strong>: kiểm tra số liệu có khớp không. Mỗi mục hiện ✓ nếu ổn, ✗ kèm danh sách đơn cần xem nếu lệch.
          </li>
          <li>
            <strong>Bút toán theo đơn</strong>: tìm đơn theo mã, email, tên tin đăng, tên hoặc mã người dùng, rồi xem dòng tiền của đơn đó. Bấm vào mã đơn để copy mã đầy đủ.
          </li>
        </ul>
      </PageHelp>
      <FlashMessages error={flash.error} notice={flash.notice} />

      <section className="card stack" style={{ overflowX: 'auto' }}>
        <div className="row spread">
          <h2>Yêu cầu rút tiền</h2>
          <select aria-label="Trạng thái" value={status} onChange={(e) => setStatus(e.target.value as WithdrawalStatus)} style={{ width: 'auto' }}>
            <option value="">Mọi trạng thái</option>
            <option value="REQUESTED">Chờ chuyển khoản</option>
            <option value="PAID">Đã chuyển</option>
            <option value="FAILED">Thất bại</option>
          </select>
        </div>
        <div className="row">
          {ownerId ? (
            <>
              <span>
                Chỉ lệnh của <UserRef id={ownerId} />
              </span>
              <button className="btn btn-ghost" onClick={() => setOwner(null)}>
                Bỏ lọc
              </button>
            </>
          ) : (
            <button className="btn btn-ghost" onClick={() => setPicking((v) => !v)}>
              {picking ? 'Đóng' : 'Lọc theo người dùng'}
            </button>
          )}
        </div>
        {picking && !ownerId && <UserSearch pickLabel="Lọc" onPick={(u) => setOwner(u.id)} />}
        {!canPayout && <p className="muted">Bạn chỉ xem được. Số tài khoản bị che, cần quyền chi trả để đánh dấu chi.</p>}
        {items && items.length === 0 && <p className="muted">Không có yêu cầu nào.</p>}
        {items && items.length > 0 && (
          <table>
            <thead>
              <tr>
                <th>Ngày</th>
                <th>Người rút</th>
                <th>Số tiền</th>
                <th>Ngân hàng</th>
                <th>Chủ tài khoản</th>
                <th>Ghi chú</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {items.map((w) => (
                <tr key={w.id}>
                  <td>{formatDateTime(w.createdAt)}</td>
                  <td>
                    <UserRef id={w.ownerId} />
                  </td>
                  <td>{formatVnd(w.amount)}</td>
                  <td>
                    {w.bank.bankName} {w.bank.bankAccountNumber}
                  </td>
                  <td>{w.bank.bankAccountName}</td>
                  <td>{w.failureReason ?? w.bankRef ?? '—'}</td>
                  <td>
                    {canPayout && w.status === 'REQUESTED' && (
                      <div className="row">
                        <button className="btn btn-primary" disabled={flash.busy} onClick={() => markPaid(w)}>
                          Đã chi
                        </button>
                        <button className="btn btn-danger" disabled={flash.busy} onClick={() => markFailed(w)}>
                          Thất bại
                        </button>
                      </div>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        <Pager result={paged.result} page={paged.page} onPage={paged.setPage} />
      </section>

      <Reconciliation />
      <OrderLedger />
    </div>
  );
}

function Reconciliation() {
  const [data, setData] = useState<Record<string, unknown> | null>(null);
  const flash = useFlash();

  const load = () => flash.run(async () => setData(await api<Record<string, unknown>>('/admin/ledger/reconciliation')));

  return (
    <section className="card stack">
      <div className="row spread">
        <h2>Đối soát sổ cái</h2>
        <button className="btn" disabled={flash.busy} onClick={load}>
          {data ? 'Chạy lại' : 'Chạy đối soát'}
        </button>
      </div>
      <FlashMessages error={flash.error} notice={flash.notice} />
      {data && <ReconciliationResult data={data} />}
    </section>
  );
}

type Rows = Record<string, unknown>[];

/** Turns the reconciliation report into a verdict per check, with the offending rows only when a check fails. */
function ReconciliationResult({ data }: { data: Record<string, unknown> }) {
  const imbalance = Number(data.totalPostingSum ?? 0);
  const depositRows = (data.depositHeldMismatches as Rows | undefined) ?? [];
  const escrowRows = (data.rentEscrowOpenOnFinalOrders as Rows | undefined) ?? [];
  const refundPayable = Number(data.refundPayable ?? 0);
  const refundsOpen = Number(data.refundsPendingOrFailed ?? 0);

  const checks: { ok: boolean; title: string; detail: string; rows?: Rows }[] = [
    {
      ok: imbalance === 0,
      title: 'Sổ cái cân bằng',
      detail: imbalance === 0 ? 'Tổng tất cả bút toán bằng 0.' : `Tổng bút toán đang lệch ${formatVnd(imbalance)}.`,
    },
    {
      ok: depositRows.length === 0,
      title: 'Tiền cọc khớp với sổ cái',
      detail: depositRows.length === 0 ? 'Mọi khoản cọc đều khớp.' : `${depositRows.length} đơn có số cọc không khớp sổ cái.`,
      rows: depositRows,
    },
    {
      ok: escrowRows.length === 0,
      title: 'Không còn tiền thuê treo ở đơn đã đóng',
      detail: escrowRows.length === 0 ? 'Đơn đã kết thúc / hủy đều đã xử lý hết tiền thuê.' : `${escrowRows.length} đơn đã đóng nhưng vẫn còn tiền thuê đang giữ.`,
      rows: escrowRows,
    },
    {
      ok: refundPayable === refundsOpen,
      title: 'Tiền phải hoàn khớp với các lệnh hoàn',
      detail: `Sổ cái ghi phải hoàn ${formatVnd(refundPayable)}, các lệnh hoàn đang chờ / lỗi là ${formatVnd(refundsOpen)}.`,
    },
  ];

  return (
    <div className="stack">
      {checks.map((c) => (
        <div key={c.title}>
          <p className={c.ok ? 'success' : 'error'} style={{ margin: 0 }}>
            {c.ok ? '✓' : '✗'} <strong>{c.title}</strong> — {c.detail}
          </p>
          {!c.ok && c.rows && c.rows.length > 0 && <DataTable rows={c.rows} />}
        </div>
      ))}
      {typeof data.checkedAt === 'string' && <p className="muted">Kiểm tra lúc {formatDateTime(data.checkedAt)}</p>}
    </div>
  );
}

const FULL_UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function OrderLedger() {
  const [params] = useSearchParams();
  const initialQ = params.get('q') ?? '';
  const [query, setQuery] = useState(initialQ);
  const [results, setResults] = useState<LedgerOrderSummary[] | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [txs, setTxs] = useState<LedgerTx[] | null>(null);
  const flash = useFlash();

  const showLedger = (id: string) =>
    flash.run(async () => {
      setSelected(id);
      setTxs(null);
      setTxs(await api<LedgerTx[]>(`/admin/ledger/orders/${id}`));
    });

  const search = (q: string) =>
    flash.run(async () => {
      setSelected(null);
      setTxs(null);
      const rows = await api<LedgerOrderSummary[]>(`/admin/ledger/orders?${new URLSearchParams({ q, size: '20' })}`);
      setResults(rows);
      if (rows.length === 1) {
        setSelected(rows[0].id);
        setTxs(await api<LedgerTx[]>(`/admin/ledger/orders/${rows[0].id}`));
      }
    });

  // Opened from a user's page (?q=email): search right away.
  useEffect(() => {
    if (initialQ) search(initialQ);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [initialQ]);

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault();
    const q = query.trim();
    if (FULL_UUID.test(q)) {
      setResults(null);
      await showLedger(q.toLowerCase());
    } else {
      await search(q);
    }
  };

  return (
    <section className="card stack" id="ledger">
      <h2>Bút toán theo đơn</h2>
      <p className="muted" style={{ margin: 0 }}>
        Tìm theo mã đơn (đủ hoặc vài ký tự đầu), email / tên / mã của người thuê hoặc chủ account, hoặc tên tin đăng. Để trống rồi bấm “Tìm” để xem các đơn mới nhất.
      </p>
      <form className="row" onSubmit={onSubmit}>
        <input
          aria-label="Tìm đơn"
          placeholder="VD: 3f2a9c1e, renter@rentgg.dev, Liên Quân…"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          style={{ maxWidth: 420 }}
        />
        <button className="btn" disabled={flash.busy}>
          Tìm
        </button>
      </form>
      <FlashMessages error={flash.error} notice={null} />

      {results && results.length === 0 && <p className="muted">Không tìm thấy đơn nào.</p>}
      {results && results.length > 0 && (
        <div style={{ overflowX: 'auto' }}>
          <table>
            <thead>
              <tr>
                <th>Mã đơn</th>
                <th>Tin đăng</th>
                <th>Người thuê</th>
                <th>Chủ account</th>
                <th>Bắt đầu</th>
                <th>Tổng thu</th>
                <th>Trạng thái</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {results.map((o) => (
                <tr key={o.id} style={o.id === selected ? { background: 'var(--bg)' } : undefined}>
                  <td>
                    <CopyId id={o.id} />
                  </td>
                  <td>
                    {o.listingTitle}
                    <div className="muted">{o.gameName}</div>
                  </td>
                  <td>{o.renterEmail}</td>
                  <td>{o.ownerEmail}</td>
                  <td>{formatDateTime(o.startAt)}</td>
                  <td>{formatVnd(o.rentAmount + o.depositAmount)}</td>
                  <td>
                    <StatusBadge status={o.status} />
                  </td>
                  <td>
                    <button className="btn" disabled={flash.busy} onClick={() => showLedger(o.id)}>
                      Xem bút toán ({o.transactionCount})
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {selected && txs && (
        <p style={{ margin: 0 }}>
          Bút toán của đơn <span className="secret">{selected}</span> <CopyId id={selected} label="copy" />
        </p>
      )}
      {txs && txs.length === 0 && <p className="muted">Đơn chưa có bút toán.</p>}
      {txs?.map((t) => (
        <div key={t.id} className="card" style={{ marginBottom: 0 }}>
          <div className="row spread">
            <strong>{labelOf(TX_TYPE, t.type)}</strong>
            <span className="muted">{formatDateTime(t.createdAt)}</span>
          </div>
          <table>
            <tbody>
              {t.postings.map((p, i) => (
                <tr key={i}>
                  <td>{labelOf(ACCOUNT_CODE, p.account)}</td>
                  <td>{p.ownerUserId ? <UserRef id={p.ownerUserId} showEmail={false} /> : ''}</td>
                  <td style={{ textAlign: 'right' }}>{p.amount.toLocaleString('vi-VN')}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ))}
    </section>
  );
}

function withdrawalQuery(status: WithdrawalStatus, ownerId: string | null) {
  const q = new URLSearchParams();
  if (status) q.set('status', status);
  if (ownerId) q.set('ownerId', ownerId);
  return q.toString();
}
