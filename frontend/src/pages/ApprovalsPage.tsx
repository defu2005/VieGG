import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { api, newIdempotencyKey } from '../api/client';
import type { Approval, ApprovalActionType, ApprovalStatus, GameView, PageResponse } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { DataList } from '../components/DataView';
import { Pager, pageAfterShrink } from '../components/Pager';
import { GameReviewQueue } from '../components/GameReviewQueue';
import { PageHelp } from '../components/PageHelp';
import { ReasonPicker } from '../components/ReasonPicker';
import { StatusBadge } from '../components/StatusBadge';
import { errorMessage, formatDateTime, formatVnd } from '../lib/format';
import { ACCOUNT_CODE, APPROVAL_ACTION, APPROVAL_STATUS, labelOf, REASON } from '../lib/labels';
import { FlashMessages, useFlash } from '../lib/useFlash';

const STATUSES: ApprovalStatus[] = ['PENDING', 'APPROVED', 'EXECUTED', 'REJECTED'];

/** GAME_ALLOW is created from the Games page (ToS change), so it is not offered here. */
const CREATABLE: ApprovalActionType[] = ['ACCOUNT_SECURITY_RESTORE', 'SELLER_HOLD_EARLY_RELEASE', 'LEDGER_ADJUSTMENT'];

const ACTION_HELP: Record<string, string> = {
  ACCOUNT_SECURITY_RESTORE: 'Mở khóa một account game đang bị tạm khóa để rà soát bảo mật, sau khi đã xác minh chính chủ.',
  SELLER_HOLD_EARLY_RELEASE: 'Cho chủ account nhận doanh thu của một đơn ngay, không chờ hết thời gian giữ.',
  LEDGER_ADJUSTMENT: 'Ghi bút toán điều chỉnh thủ công để sửa sai lệch số liệu. Tổng các dòng phải bằng 0.',
};

export function ApprovalsPage() {
  const { can } = useAuth();
  const canListApprovals = can('approval:list');
  const [status, setStatus] = useState<ApprovalStatus>('PENDING');
  const [page, setPage] = useState(0);
  const [result, setResult] = useState<PageResponse<Approval> | null>(null);
  const items = result?.content ?? null;
  const [gameNames, setGameNames] = useState<Record<string, string>>({});
  const flash = useFlash();

  useEffect(() => {
    api<GameView[]>('/games?all=true')
      .then((gs) => setGameNames(Object.fromEntries(gs.map((g) => [g.id, g.name]))))
      .catch(() => setGameNames({}));
  }, []);

  const load = useCallback(() => {
    if (!canListApprovals) return;
    api<PageResponse<Approval>>(`/admin/approvals?status=${status}&page=${page}&size=20`)
      .then((r) => {
        const back = pageAfterShrink(r, page);
        if (back != null) setPage(back);
        else setResult(r);
      })
      .catch((err) => flash.setError(errorMessage(err)));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [status, page, canListApprovals]);

  useEffect(() => {
    load();
  }, [load]);

  const decide = async (a: Approval, action: 'approve' | 'reject') => {
    let note: string | undefined;
    if (action === 'reject') {
      note = window.prompt('Lý do từ chối (có thể để trống):') ?? undefined;
    } else if (!window.confirm(`Duyệt "${labelOf(APPROVAL_ACTION, a.actionType)}"? Hành động sẽ được thực hiện ngay.`)) {
      return;
    }
    const ok = await flash.run(
      () => api(`/admin/approvals/${a.id}/${action}`, { method: 'POST', body: action === 'reject' ? { note } : undefined }),
      action === 'approve' ? 'Đã duyệt và thực hiện.' : 'Đã từ chối.',
    );
    if (ok) load();
  };

  return (
    <div className="stack">
      <h1>Phê duyệt</h1>
      <PageHelp id="approvals" title="Phê duyệt hai người">
        <p>Các thao tác nhạy cảm (mở khóa account, nhả doanh thu sớm, điều chỉnh sổ cái, mở lại game) cần hai người: một người tạo yêu cầu, một người khác kiểm tra và duyệt. Người tạo không tự duyệt được.</p>
        <p>Yêu cầu được thực hiện ngay khi bấm “Duyệt”.</p>
      </PageHelp>
      {can('game:read_hidden') && <GameReviewQueue />}

      {canListApprovals && (
        <section className="stack">
          <div className="row spread">
            <h2>Yêu cầu phê duyệt</h2>
            <select aria-label="Trạng thái" value={status} onChange={(e) => {
              setStatus(e.target.value as ApprovalStatus);
              setPage(0);
              setResult(null);
            }} style={{ width: 'auto' }}>
              {STATUSES.map((s) => (
                <option key={s} value={s}>
                  {labelOf(APPROVAL_STATUS, s)}
                </option>
              ))}
            </select>
          </div>
          <FlashMessages error={flash.error} notice={flash.notice} />

          <CreateApproval
            onCreated={() => {
              flash.setError(null);
              flash.setNotice('Đã tạo yêu cầu phê duyệt. Cần một người khác duyệt.');
              load();
            }}
            onError={(err) => flash.setError(errorMessage(err))}
          />

          {items && items.length === 0 && <p className="muted">Không có yêu cầu nào.</p>}
          {items?.map((a) => (
            <div key={a.id} className="card stack">
              <div className="row spread">
                <strong>{labelOf(APPROVAL_ACTION, a.actionType)}</strong>
                <StatusBadge status={a.status} dict={APPROVAL_STATUS} />
              </div>
              <p className="muted" style={{ margin: 0 }}>
                Lý do: {labelOf(REASON, a.reasonCode)} · tạo lúc {formatDateTime(a.createdAt)} · hết hạn {formatDateTime(a.expiresAt)}
              </p>
              <DataList data={a.payload} names={gameNames} />
              {a.resultNote && <p className="muted">Kết quả: {a.resultNote}</p>}
              {a.status === 'PENDING' && (
                <div className="row">
                  <button className="btn btn-primary" disabled={flash.busy} onClick={() => decide(a, 'approve')}>
                    Duyệt
                  </button>
                  <button className="btn btn-danger" disabled={flash.busy} onClick={() => decide(a, 'reject')}>
                    Từ chối
                  </button>
                </div>
              )}
            </div>
          ))}
          <Pager result={result} page={page} onPage={setPage} />
        </section>
      )}
    </div>
  );
}

interface PostingDraft {
  accountCode: string;
  ownerUserId: string;
  amount: number;
}

const emptyPosting = (): PostingDraft => ({ accountCode: 'PLATFORM_REVENUE', ownerUserId: '', amount: 0 });
const isSellerAccount = (code: string) => code.startsWith('SELLER_');

function CreateApproval({ onCreated, onError }: { onCreated: () => void; onError: (err: unknown) => void }) {
  const [open, setOpen] = useState(false);
  const [actionType, setActionType] = useState<ApprovalActionType>('ACCOUNT_SECURITY_RESTORE');
  const [targetId, setTargetId] = useState('');
  const [postings, setPostings] = useState<PostingDraft[]>([emptyPosting(), { ...emptyPosting(), accountCode: 'SELLER_AVAILABLE' }]);
  const [orderId, setOrderId] = useState('');
  const [reasonCode, setReasonCode] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const total = postings.reduce((s, p) => s + (Number(p.amount) || 0), 0);
  const updatePosting = (i: number, patch: Partial<PostingDraft>) => setPostings(postings.map((p, k) => (k === i ? { ...p, ...patch } : p)));

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault();
    if (actionType === 'LEDGER_ADJUSTMENT' && total !== 0) {
      onError(new Error('Tổng các dòng bút toán phải bằng 0.'));
      return;
    }
    setSubmitting(true);
    try {
      let payload: Record<string, unknown>;
      if (actionType === 'ACCOUNT_SECURITY_RESTORE') payload = { gameAccountId: targetId.trim() };
      else if (actionType === 'SELLER_HOLD_EARLY_RELEASE') payload = { orderId: targetId.trim() };
      else
        payload = {
          postings: postings.map((p) => ({
            accountCode: p.accountCode,
            amount: Number(p.amount),
            ...(isSellerAccount(p.accountCode) && p.ownerUserId.trim() ? { ownerUserId: p.ownerUserId.trim() } : {}),
          })),
          ...(orderId.trim() ? { orderId: orderId.trim() } : {}),
        };
      await api('/admin/approvals', {
        method: 'POST',
        body: { actionType, payload, reasonCode },
        idempotencyKey: newIdempotencyKey(),
      });
      setTargetId('');
      setReasonCode('');
      setOpen(false);
      onCreated();
    } catch (err) {
      onError(err);
    } finally {
      setSubmitting(false);
    }
  };

  if (!open) {
    return (
      <div>
        <button className="btn" onClick={() => setOpen(true)}>
          Tạo yêu cầu mới
        </button>
      </div>
    );
  }

  return (
    <form className="card form wide" onSubmit={onSubmit}>
      <h2>Yêu cầu phê duyệt mới</h2>
      <div className="field">
        <label htmlFor="ap-type">Bạn muốn làm gì?</label>
        <select id="ap-type" value={actionType} onChange={(e) => setActionType(e.target.value as ApprovalActionType)}>
          {CREATABLE.map((t) => (
            <option key={t} value={t}>
              {labelOf(APPROVAL_ACTION, t)}
            </option>
          ))}
        </select>
        <span className="field-hint">{ACTION_HELP[actionType]}</span>
      </div>
      {actionType === 'LEDGER_ADJUSTMENT' ? (
        <>
          <div className="field">
            <span style={{ fontWeight: 600, fontSize: '0.9rem' }}>Các dòng bút toán</span>
            <span className="field-hint">Số dương là ghi tăng, số âm là ghi giảm. Tài khoản của chủ account cần mã người dùng.</span>
          </div>
          {postings.map((p, i) => (
            <div key={i} className="posting-row">
              <select aria-label={`Tài khoản dòng ${i + 1}`} value={p.accountCode} onChange={(e) => updatePosting(i, { accountCode: e.target.value })}>
                {Object.keys(ACCOUNT_CODE).map((c) => (
                  <option key={c} value={c}>
                    {labelOf(ACCOUNT_CODE, c)}
                  </option>
                ))}
              </select>
              <input
                aria-label={`Mã chủ account dòng ${i + 1}`}
                placeholder={isSellerAccount(p.accountCode) ? 'Mã chủ account' : 'Không cần'}
                disabled={!isSellerAccount(p.accountCode)}
                required={isSellerAccount(p.accountCode)}
                value={isSellerAccount(p.accountCode) ? p.ownerUserId : ''}
                onChange={(e) => updatePosting(i, { ownerUserId: e.target.value })}
              />
              <input aria-label={`Số tiền dòng ${i + 1}`} type="number" required value={p.amount || ''} onChange={(e) => updatePosting(i, { amount: Number(e.target.value) })} />
              <button type="button" className="btn btn-ghost" disabled={postings.length <= 2} onClick={() => setPostings(postings.filter((_, k) => k !== i))} aria-label={`Xóa dòng ${i + 1}`}>
                ×
              </button>
            </div>
          ))}
          <div className="row spread">
            <button type="button" className="btn" onClick={() => setPostings([...postings, emptyPosting()])}>
              + Thêm dòng
            </button>
            <span className={total === 0 ? 'success' : 'error'} style={{ margin: 0 }}>
              Tổng: {formatVnd(total)} {total === 0 ? '✓ cân bằng' : '(phải bằng 0)'}
            </span>
          </div>
          <div className="field">
            <label htmlFor="ap-order">Mã đơn liên quan (nếu có)</label>
            <input id="ap-order" value={orderId} onChange={(e) => setOrderId(e.target.value)} />
          </div>
        </>
      ) : (
        <div className="field">
          <label htmlFor="ap-target">{actionType === 'ACCOUNT_SECURITY_RESTORE' ? 'Mã account game' : 'Mã đơn thuê'}</label>
          <input id="ap-target" required value={targetId} onChange={(e) => setTargetId(e.target.value)} />
        </div>
      )}
      <ReasonPicker id="ap-reason" value={reasonCode} onChange={setReasonCode} options={['OWNER_VERIFIED', 'RISK_CLEARED', 'CORRECTION', 'GOODWILL', 'ISSUE_RESOLVED']} />
      <div className="row">
        <button className="btn btn-primary" disabled={submitting || !reasonCode.trim()}>
          Gửi yêu cầu
        </button>
        <button type="button" className="btn btn-ghost" onClick={() => setOpen(false)}>
          Hủy
        </button>
      </div>
    </form>
  );
}
