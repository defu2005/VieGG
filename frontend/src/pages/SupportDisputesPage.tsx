import { useEffect, useState, type FormEvent } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { api } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import { UserRef } from '../components/UserRef';
import type { Dispute, DisputeItem, DisputeStatus, DisputeType, Order, OrderDetail, OrderRisk, UserStatus } from '../api/types';
import {
  DISPUTE_TYPE_LABEL,
  DisputeItemList,
  disputeTitle,
  EvidenceList,
  ITEM_STATUS_LABEL,
  itemBadgeClass,
  orderRisks,
} from '../components/DisputesSection';
import { PageHelp } from '../components/PageHelp';
import { Pager, usePaged } from '../components/Pager';
import { ReasonPicker } from '../components/ReasonPicker';
import { StatusBadge } from '../components/StatusBadge';
import { errorMessage, formatDateTime, formatVnd } from '../lib/format';
import { DISPUTE_STATUS, labelOf, REASON, USER_STATUS } from '../lib/labels';
import { RISK_FEE_NOTICE } from '../lib/riskFee';
import { FlashMessages, useFlash } from '../lib/useFlash';

const STATUSES: DisputeStatus[] = ['OPEN', 'RESOLVED', 'REJECTED'];

export function SupportDisputesPage() {
  const [params, setParams] = useSearchParams();
  const q = params.get('q') ?? '';
  const [qInput, setQInput] = useState(q);
  // Coming from a search link (e.g. a user's page): show every status, not only open disputes.
  const [status, setStatus] = useState<DisputeStatus | ''>(q ? '' : 'OPEN');
  const [type, setType] = useState<DisputeType | ''>('');
  const flash = useFlash();
  const query = new URLSearchParams();
  if (status) query.set('status', status);
  if (type) query.set('type', type);
  if (q) query.set('q', q);
  const paged = usePaged<Dispute>(`/support/disputes?${query}`, { onError: (err) => flash.setError(errorMessage(err)) });
  const items = paged.items;
  const load = paged.reload;

  const onSearch = (e: FormEvent) => {
    e.preventDefault();
    const v = qInput.trim();
    setParams(v ? { q: v } : {});
  };

  return (
    <div className="stack">
      <div className="row spread">
        <h1>Khiếu nại</h1>
        <div className="row">
          <select aria-label="Trạng thái" value={status} onChange={(e) => setStatus(e.target.value as DisputeStatus | '')} style={{ width: 'auto' }}>
            <option value="">Mọi trạng thái</option>
            {STATUSES.map((s) => (
              <option key={s} value={s}>
                {labelOf(DISPUTE_STATUS, s)}
              </option>
            ))}
          </select>
          <select aria-label="Loại" value={type} onChange={(e) => setType(e.target.value as DisputeType | '')} style={{ width: 'auto' }}>
            <option value="">Tất cả loại</option>
            {(Object.keys(DISPUTE_TYPE_LABEL) as DisputeType[]).map((t) => (
              <option key={t} value={t}>
                {DISPUTE_TYPE_LABEL[t]}
              </option>
            ))}
          </select>
        </div>
      </div>
      <PageHelp id="support-disputes" title="Phân xử khiếu nại">
        <p>Đọc mô tả và bằng chứng của hai bên, rồi chọn “Chấp nhận” hoặc “Bác” khiếu nại.</p>
        <ul>
          <li>
            <strong>Khấu trừ cọc</strong>: số tiền lấy từ cọc của người thuê để bồi thường chủ account.
          </li>
          <li>
            <strong>Hoàn tiền thuê</strong>: số tiền trả lại cho người thuê.
          </li>
          <li>Có thể hạn chế hoặc khóa người thuê nếu vi phạm nghiêm trọng.</li>
          <li>
            Khiếu nại rủi ro của người thuê có thể gồm nhiều rủi ro: chấp nhận hoặc bác <strong>từng rủi ro</strong>. Khi xét
            xong rủi ro cuối, hệ thống tự hoàn tiền thuê còn giữ (nếu có rủi ro được chấp nhận) và chuyển đền bù của các rủi
            ro được chấp nhận.
          </li>
          <li>{RISK_FEE_NOTICE} Số tiền nhập ở đây là số người trả trả; hệ thống tự trừ phí khi chuyển.</li>
        </ul>
      </PageHelp>
      <form className="row" role="search" onSubmit={onSearch}>
        <input
          type="search"
          aria-label="Tìm khiếu nại"
          placeholder="Mã khiếu nại / mã đơn, hoặc email, tên, mã của người thuê / chủ account"
          value={qInput}
          onChange={(e) => setQInput(e.target.value)}
          style={{ flex: '1 1 260px', maxWidth: 520 }}
        />
        <button className="btn btn-primary">Tìm</button>
        {q && (
          <button
            type="button"
            className="btn btn-ghost"
            onClick={() => {
              setQInput('');
              setParams({});
            }}
          >
            Xoá tìm kiếm
          </button>
        )}
      </form>
      <FlashMessages error={flash.error} notice={flash.notice} />
      {items && items.length === 0 && <p className="muted">{q ? `Không có khiếu nại khớp “${q}”.` : 'Không có khiếu nại.'}</p>}
      {items?.map((d) => (
        <DisputeReview
          key={d.id}
          dispute={d}
          onResolved={() => {
            flash.setError(null);
            flash.setNotice('Đã ghi nhận quyết định.');
            load();
          }}
        />
      ))}
      <Pager result={paged.result} page={paged.page} onPage={paged.setPage} />
    </div>
  );
}

function DisputeReview({ dispute: d, onResolved }: { dispute: Dispute; onResolved: () => void }) {
  const flash = useFlash();
  const { can } = useAuth();
  const navigate = useNavigate();
  const [decision, setDecision] = useState<'RESOLVE' | 'REJECT'>('RESOLVE');
  const [depositDeduction, setDepositDeduction] = useState(0);
  const [rentRefund, setRentRefund] = useState(0);
  const [compensation, setCompensation] = useState(0);
  const [risks, setRisks] = useState<OrderRisk[]>([]);
  const [order, setOrder] = useState<Order | null>(null);
  const [reasonCode, setReasonCode] = useState('');
  const perRisk = (d.items?.length ?? 0) > 0;

  // Risk-based disputes: the risk's terms are frozen in the order snapshot.
  useEffect(() => {
    if (!d.riskId && !perRisk) return;
    api<OrderDetail>(`/orders/${d.orderId}`)
      .then((od) => {
        setRisks(orderRisks(od.order));
        setOrder(od.order);
      })
      .catch(() => setRisks([]));
  }, [d.orderId, d.riskId, perRisk]);
  const risk = d.riskId ? risks.find((r) => r.riskId === d.riskId) : undefined;
  const [note, setNote] = useState('');
  const [renterStatus, setRenterStatus] = useState<UserStatus | ''>('');

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault();
    const ok = await flash.run(() =>
      api(`/support/disputes/${d.id}/resolve`, {
        method: 'POST',
        body: {
          decision,
          depositDeduction,
          rentRefund,
          compensation,
          reasonCode,
          note: note || undefined,
          renterStatus: renterStatus || undefined,
        },
      }),
    );
    if (ok) onResolved();
  };

  return (
    <div className="card stack">
      <div className="row spread">
        <strong>{disputeTitle(d, risks)}</strong>
        <span className="muted">
          Đơn <Link to={`/orders/${d.orderId}`}>{d.orderId.slice(0, 8)}</Link> · mở bởi <UserRef id={d.openedBy} showEmail={false} /> lúc {formatDateTime(d.createdAt)}
          {d.deadlineAt ? ` · hạn ${formatDateTime(d.deadlineAt)}` : ''}
        </span>
      </div>
      {can('helpdesk:handle') && (
        <div className="row">
          <button
            type="button"
            className="btn btn-sm"
            disabled={flash.busy}
            title="Mời người thuê và chủ account vào cùng một cuộc trò chuyện với bạn"
            onClick={() =>
              flash.run(async () => {
                const t = await api<{ id: string }>('/support/tickets', {
                  method: 'POST',
                  body: {
                    category: 'MEDIATION',
                    disputeId: d.id,
                    subject: `Đàm phán khiếu nại đơn ${d.orderId.slice(0, 8)}`,
                    body: 'Chào hai bạn, mình là hỗ trợ viên phụ trách khiếu nại này. Hai bên trao đổi tại đây để cùng thống nhất cách giải quyết nhé.',
                  },
                });
                navigate(`/tickets/${t.id}`);
              })
            }
          >
            💬 Mở phòng đàm phán
          </button>
        </div>
      )}
      {risk && !perRisk && (
        <p className="banner" style={{ margin: 0 }}>
          {risk.side === 'OWNER'
            ? risk.amount != null
              ? `Chủ account đặt cọc ${formatVnd(risk.amount)} cho rủi ro này: khấu trừ tối đa bằng số đó. ${
                  risk.stackable === false
                    ? 'Không cộng dồn: nếu chấp nhận, đơn chỉ bị khấu trừ khoản này, các khấu trừ cộng dồn khác bị bỏ qua.'
                    : 'Cộng dồn với các rủi ro cộng dồn khác của đơn.'
                }`
              : 'Listing cũ chưa tách cọc theo rủi ro: khấu trừ tối đa bằng tiền cọc của đơn.'
            : risk.amount != null
              ? `Chủ account cam kết đền bù tối đa ${formatVnd(risk.amount)} (trừ từ doanh thu đơn, rồi ví chủ account), ngoài phần hoàn tiền thuê.`
              : 'Chủ account không cam kết đền bù: chỉ hoàn tiền thuê.'}
        </p>
      )}
      <p style={{ whiteSpace: 'pre-line' }}>{d.description}</p>
      <EvidenceList disputeId={d.id} evidence={d.evidence} />

      {perRisk && d.status === 'OPEN' ? (
        <RiskItemsReview dispute={d} items={d.items!} risks={risks} order={order} onDecided={onResolved} />
      ) : perRisk ? (
        <>
          <DisputeItemList items={d.items!} risks={risks} />
          <p className="muted">
            <StatusBadge status={d.status} dict={DISPUTE_STATUS} /> · tự tính: hoàn tiền thuê {formatVnd(d.rentRefund)} · đền bù{' '}
            {formatVnd(d.compensation)}
          </p>
        </>
      ) : d.status !== 'OPEN' ? (
        <p className="muted">
          <StatusBadge status={d.status} dict={DISPUTE_STATUS} /> · khấu trừ cọc {formatVnd(d.depositDeduction)} · hoàn tiền thuê {formatVnd(d.rentRefund)}
          {d.compensation > 0 ? ` · đền bù ${formatVnd(d.compensation)}` : ''} · lý do:{' '}
          {labelOf(REASON, d.reasonCode)}
          {d.decisionNote ? ` — ${d.decisionNote}` : ''}
        </p>
      ) : (
        <form className="form wide" onSubmit={onSubmit}>
          <div className="grid">
            <div className="field">
              <label htmlFor={`dec-${d.id}`}>Quyết định</label>
              <select id={`dec-${d.id}`} value={decision} onChange={(e) => setDecision(e.target.value as 'RESOLVE' | 'REJECT')}>
                <option value="RESOLVE">Chấp nhận khiếu nại</option>
                <option value="REJECT">Bác khiếu nại</option>
              </select>
            </div>
            <div className="field">
              <label htmlFor={`ded-${d.id}`}>Khấu trừ cọc (đ)</label>
              <input id={`ded-${d.id}`} type="number" min={0} value={depositDeduction} onChange={(e) => setDepositDeduction(Number(e.target.value))} />
            </div>
            <div className="field">
              <label htmlFor={`ref-${d.id}`}>Hoàn tiền thuê (đ)</label>
              <input id={`ref-${d.id}`} type="number" min={0} value={rentRefund} onChange={(e) => setRentRefund(Number(e.target.value))} />
            </div>
            {d.type === 'RENTER_RISK' && (
              <div className="field">
                <label htmlFor={`comp-${d.id}`}>Đền bù từ chủ account (đ)</label>
                <input
                  id={`comp-${d.id}`}
                  type="number"
                  min={0}
                  max={risk?.amount ?? 0}
                  disabled={!risk?.amount}
                  value={compensation}
                  onChange={(e) => setCompensation(Number(e.target.value))}
                />
                <span className="field-hint">{risk?.amount ? `Tối đa ${formatVnd(risk.amount)}` : 'Không có đền bù'}</span>
              </div>
            )}
            <ReasonPicker
              id={`rc-${d.id}`}
              value={reasonCode}
              onChange={setReasonCode}
              options={['EVIDENCE_CONFIRMED', 'INSUFFICIENT_EVIDENCE', 'OWNER_FAULT', 'RENTER_FAULT', 'ISSUE_RESOLVED', 'GOODWILL']}
            />
            <div className="field">
              <label htmlFor={`rs-${d.id}`}>Xử lý tài khoản người thuê</label>
              <select id={`rs-${d.id}`} value={renterStatus} onChange={(e) => setRenterStatus(e.target.value as UserStatus | '')}>
                <option value="">Giữ nguyên</option>
                {Object.keys(USER_STATUS).map((s) => (
                  <option key={s} value={s}>
                    {labelOf(USER_STATUS, s)}
                  </option>
                ))}
              </select>
            </div>
          </div>
          <div className="field">
            <label htmlFor={`note-${d.id}`}>Ghi chú</label>
            <textarea id={`note-${d.id}`} maxLength={4000} value={note} onChange={(e) => setNote(e.target.value)} />
          </div>
          <FlashMessages error={flash.error} notice={flash.notice} />
          <div>
            <button className="btn btn-primary" disabled={flash.busy || !reasonCode.trim()}>
              Ghi nhận quyết định
            </button>
          </div>
        </form>
      )}
    </div>
  );
}

/**
 * Renter complaint with several risks: support accepts or rejects each one. The last decision closes the
 * complaint; the backend then refunds the rent still held (once, if any risk is accepted) and pays the owner's
 * compensation of every accepted risk.
 */
function RiskItemsReview({
  dispute: d,
  items,
  risks,
  order,
  onDecided,
}: {
  dispute: Dispute;
  items: DisputeItem[];
  risks: OrderRisk[];
  order: Order | null;
  onDecided: () => void;
}) {
  const riskOf = (i: DisputeItem) => risks.find((r) => r.riskId === i.riskId);
  const accepted = items.filter((i) => i.status === 'ACCEPTED');
  const open = items.filter((i) => i.status === 'OPEN');
  const compAccepted = accepted.reduce((sum, i) => sum + i.compensation, 0);
  const compIfAllOpenAccepted = compAccepted + open.reduce((sum, i) => sum + (riskOf(i)?.amount ?? 0), 0);
  const rent = order ? formatVnd(order.rentAmount) : 'tiền thuê';

  return (
    <div className="stack">
      <div className="banner" style={{ margin: 0 }}>
        <strong>Tiền sẽ được tính tự động</strong> khi xét xong {items.length} rủi ro ({open.length} còn chờ).
        <ul style={{ margin: '0.25rem 0 0' }}>
          <li>Có ít nhất một rủi ro được chấp nhận → hoàn phần tiền thuê còn giữ (tối đa {rent}), một lần.</li>
          <li>
            Đền bù từ chủ account = tổng mức đền bù của các rủi ro được chấp nhận: hiện {formatVnd(compAccepted)}
            {open.length > 0 ? `, tối đa ${formatVnd(compIfAllOpenAccepted)} nếu chấp nhận hết` : ''}.
          </li>
          <li>Bác mọi rủi ro → khiếu nại bị bác, không chuyển tiền.</li>
        </ul>
      </div>
      {items.map((i) => (
        <RiskItemRow key={i.id} disputeId={d.id} item={i} risk={riskOf(i)} isLast={open.length === 1 && i.status === 'OPEN'} onDecided={onDecided} />
      ))}
    </div>
  );
}

function RiskItemRow({
  disputeId,
  item: i,
  risk,
  isLast,
  onDecided,
}: {
  disputeId: string;
  item: DisputeItem;
  risk: OrderRisk | undefined;
  isLast: boolean;
  onDecided: () => void;
}) {
  const flash = useFlash();
  const [reasonCode, setReasonCode] = useState('');
  const [note, setNote] = useState('');
  const terms =
    risk?.amount != null ? `Đền bù ${formatVnd(risk.amount)} nếu chấp nhận (trừ doanh thu đơn, rồi ví chủ account).` : 'Không có đền bù: chỉ hoàn tiền thuê.';

  const decide = async (decision: 'ACCEPT' | 'REJECT') => {
    const ok = await flash.run(() =>
      api(`/support/disputes/${disputeId}/items/${i.id}/decide`, {
        method: 'POST',
        body: { decision, reasonCode, note: note || undefined },
      }),
    );
    if (ok) onDecided();
  };

  return (
    <div className="card" style={{ marginBottom: 0 }}>
      <div className="row spread">
        <strong>{risk?.name ?? 'Rủi ro'}</strong>
        <span className={`badge ${itemBadgeClass(i.status)}`}>{ITEM_STATUS_LABEL[i.status]}</span>
      </div>
      {risk?.description && <p className="muted" style={{ margin: '0.25rem 0' }}>{risk.description}</p>}
      <p className="field-hint" style={{ margin: '0.25rem 0' }}>
        {i.status === 'ACCEPTED' ? `Đền bù: ${formatVnd(i.compensation)}` : i.status === 'OPEN' ? terms : 'Không đền bù.'}
      </p>
      {i.status !== 'OPEN' ? (
        <p className="muted" style={{ margin: 0 }}>
          {labelOf(REASON, i.reasonCode)}
          {i.note ? ` — ${i.note}` : ''}
        </p>
      ) : (
        <div className="form wide">
          <div className="grid">
            <ReasonPicker
              id={`irc-${i.id}`}
              value={reasonCode}
              onChange={setReasonCode}
              options={['EVIDENCE_CONFIRMED', 'OWNER_FAULT', 'INSUFFICIENT_EVIDENCE', 'ISSUE_RESOLVED', 'RENTER_FAULT', 'FALSE_CLAIM']}
            />
            <div className="field">
              <label htmlFor={`inote-${i.id}`}>Ghi chú</label>
              <input id={`inote-${i.id}`} maxLength={4000} value={note} onChange={(e) => setNote(e.target.value)} />
            </div>
          </div>
          <FlashMessages error={flash.error} notice={flash.notice} />
          <div className="row">
            <button type="button" className="btn btn-primary" disabled={flash.busy || !reasonCode.trim()} onClick={() => decide('ACCEPT')}>
              Chấp nhận rủi ro này
            </button>
            <button type="button" className="btn btn-danger" disabled={flash.busy || !reasonCode.trim()} onClick={() => decide('REJECT')}>
              Bác
            </button>
            {isLast && <span className="muted">Đây là rủi ro cuối: quyết định sẽ đóng khiếu nại và chuyển tiền.</span>}
          </div>
        </div>
      )}
    </div>
  );
}
