import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { api } from '../api/client';
import type { Dispute, DisputeItem, DisputeItemStatus, DisputeType, Order, OrderRisk } from '../api/types';
import { errorMessage, formatDateTime, formatVnd } from '../lib/format';
import { labelOf, REASON } from '../lib/labels';
import { netText, RISK_FEE_PERCENT, riskFee, riskNet } from '../lib/riskFee';
import { FlashMessages, useFlash } from '../lib/useFlash';
import {
  DisputeAttachmentView,
  DisputeFilePicker,
  isAttachment,
  MAX_DISPUTE_FILES,
  uploadDisputeFiles,
} from './DisputeMedia';

export const DISPUTE_TYPE_LABEL: Record<DisputeType, string> = {
  OWNER_DAMAGE: 'Chủ account báo thiệt hại',
  ACCOUNT_TAKEOVER: 'Báo bị chiếm account',
  RENTER_LOGIN_FAILED: 'Không đăng nhập được',
  RENTER_NOT_AS_DESCRIBED: 'Account không đúng mô tả',
  OWNER_RISK: 'Rủi ro của chủ account',
  RENTER_RISK: 'Rủi ro của người thuê',
};

const STATUS_LABEL: Record<string, string> = { OPEN: 'Đang xử lý', RESOLVED: 'Đã giải quyết', REJECTED: 'Bị bác' };

const RENTER_TYPES: DisputeType[] = ['RENTER_LOGIN_FAILED', 'RENTER_NOT_AS_DESCRIBED'];
const OWNER_TYPES: DisputeType[] = ['OWNER_DAMAGE', 'ACCOUNT_TAKEOVER'];

/** Risks frozen in the order at booking (empty for orders made before risks existed). */
export function orderRisks(o: Order): OrderRisk[] {
  const raw = o.policySnapshot?.risks;
  return Array.isArray(raw) ? (raw as OrderRisk[]) : [];
}

/** Title of a dispute: the risk name(s) for risk-based ones. */
export function disputeTitle(d: Dispute, risks: OrderRisk[]): string {
  const ids = d.items && d.items.length > 0 ? d.items.map((i) => i.riskId) : d.riskId ? [d.riskId] : [];
  const names = ids.map((id) => risks.find((r) => r.riskId === id)?.name).filter(Boolean);
  return names.length > 0 ? `${DISPUTE_TYPE_LABEL[d.type]}: ${names.join(', ')}` : DISPUTE_TYPE_LABEL[d.type];
}

export const ITEM_STATUS_LABEL: Record<DisputeItemStatus, string> = {
  OPEN: 'Chờ xử lý',
  ACCEPTED: 'Chấp nhận',
  REJECTED: 'Bác',
};

export function itemBadgeClass(s: DisputeItemStatus): string {
  return s === 'OPEN' ? 'badge-warn' : s === 'ACCEPTED' ? 'badge-ok' : 'badge-bad';
}

/** The risks of a renter complaint with their decision, as read-only rows. */
export function DisputeItemList({ items, risks }: { items: DisputeItem[]; risks: OrderRisk[] }) {
  return (
    <ul className="evidence-list" style={{ listStyle: 'none', paddingLeft: 0 }}>
      {items.map((i) => {
        const risk = risks.find((r) => r.riskId === i.riskId);
        return (
          <li key={i.id} className="row spread" style={{ padding: '0.25rem 0' }}>
            <span>
              {risk?.name ?? 'Rủi ro'}
              {i.status === 'ACCEPTED' && i.compensation > 0 ? ` — đền bù ${formatVnd(i.compensation)}` : ''}
              {i.status !== 'OPEN' && i.reasonCode ? (
                <span className="muted">
                  {' '}
                  · {labelOf(REASON, i.reasonCode)}
                  {i.note ? ` — ${i.note}` : ''}
                </span>
              ) : null}
            </span>
            <span className={`badge ${itemBadgeClass(i.status)}`}>{ITEM_STATUS_LABEL[i.status]}</span>
          </li>
        );
      })}
    </ul>
  );
}

/** One choice of the "open dispute" form: a plain type, or a risk of the order. */
interface DisputeChoice {
  key: string;
  type: DisputeType;
  riskId: string | null;
  label: string;
  hint: string | null;
}

function windowText(r: OrderRisk): string | null {
  if (r.complaintWindowMinutes == null) return null;
  return r.side === 'RENTER'
    ? `Chỉ khiếu nại được trong ${r.complaintWindowMinutes} phút sau khi xem thông tin tài khoản.`
    : `Chỉ khiếu nại được trong ${r.complaintWindowMinutes} phút sau khi kết thúc thuê.`;
}

function choicesFor(isRenter: boolean, isOwner: boolean, risks: OrderRisk[]): DisputeChoice[] {
  if (risks.length === 0) {
    const types = isRenter ? RENTER_TYPES : isOwner ? OWNER_TYPES : [];
    return types.map((t) => ({ key: t, type: t, riskId: null, label: DISPUTE_TYPE_LABEL[t], hint: null }));
  }
  const side = isRenter ? 'RENTER' : isOwner ? 'OWNER' : null;
  if (!side) return [];
  const out: DisputeChoice[] = risks
    // Owner risks the owner chose not to cover cannot be claimed.
    .filter((r) => r.side === side && r.covered !== false)
    .map((r) => {
      const money =
        side === 'OWNER'
          ? (r.amount != null
              ? `Khấu trừ tối đa ${formatVnd(r.amount)} từ tiền cọc (bạn ${netText(r.amount)}).`
              : `Khấu trừ từ tiền cọc của đơn (VieGG thu phí ${RISK_FEE_PERCENT}%).`) +
            (r.stackable === false
              ? ' Không cộng dồn: nếu được chấp nhận, người thuê chỉ trả khoản này (thay cho các khấu trừ khác).'
              : ' Cộng dồn với các rủi ro cộng dồn khác.')
          : r.amount != null
            ? `Hoàn tiền thuê + đền bù tối đa ${formatVnd(r.amount)} (bạn ${netText(r.amount)}).`
            : 'Được hoàn tiền thuê (chủ account không cam kết đền bù).';
      return {
        key: `risk:${r.riskId}`,
        type: side === 'OWNER' ? 'OWNER_RISK' : 'RENTER_RISK',
        riskId: r.riskId,
        label: r.name,
        hint: [money, windowText(r)].filter(Boolean).join(' '),
      };
    });
  if (side === 'OWNER') {
    out.push({ key: 'ACCOUNT_TAKEOVER', type: 'ACCOUNT_TAKEOVER', riskId: null, label: DISPUTE_TYPE_LABEL.ACCOUNT_TAKEOVER, hint: 'Khẩn cấp: kết thúc thuê ngay và khóa account để kiểm tra.' });
  }
  return out;
}

/** One URL per line; the backend accepts https:// links only. */
export function parseUrls(text: string): string[] {
  return text
    .split('\n')
    .map((s) => s.trim())
    .filter(Boolean);
}

export function EvidenceList({ disputeId, evidence }: { disputeId: string; evidence: Record<string, unknown>[] }) {
  if (evidence.length === 0) return <p className="muted">Chưa có bằng chứng.</p>;
  const media = evidence.filter(isAttachment);
  const others = evidence.filter((e) => !isAttachment(e));
  return (
    <>
      {media.length > 0 && (
        <div className="evidence-media-grid">
          {media.map((m) => (
            <figure key={m.attachmentId}>
              <DisputeAttachmentView disputeId={disputeId} attachment={m} />
              {m.note && <figcaption className="muted">{m.note}</figcaption>}
            </figure>
          ))}
        </div>
      )}
      {others.length > 0 && <TextEvidence evidence={others} />}
    </>
  );
}

function TextEvidence({ evidence }: { evidence: Record<string, unknown>[] }) {
  return (
    <ul className="evidence-list">
      {evidence.map((e, i) => (
        <li key={i} className="muted">
          {typeof e.url === 'string' ? (
            <a href={e.url} target="_blank" rel="noreferrer noopener">
              {e.url}
            </a>
          ) : (
            Object.entries(e)
              .filter(([k, v]) => k !== 'note' && v != null && typeof v !== 'object')
              .map(([, v]) => String(v))
              .join(' · ') || 'Bằng chứng'
          )}
          {typeof e.note === 'string' && e.note ? ` — ${e.note}` : ''}
        </li>
      ))}
    </ul>
  );
}

export function DisputesSection({
  orderId,
  orderStatus,
  isRenter,
  isOwner,
  risks = [],
  handoverConfirmedAt = null,
}: {
  orderId: string;
  orderStatus: string;
  isRenter: boolean;
  isOwner: boolean;
  risks?: OrderRisk[];
  /** Once the renter confirms the account works, the backend refuses their complaints. */
  handoverConfirmedAt?: string | null;
}) {
  const [disputes, setDisputes] = useState<Dispute[] | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);

  const load = useCallback(() => {
    api<Dispute[]>(`/orders/${orderId}/disputes`)
      .then(setDisputes)
      .catch((err) => setLoadError(errorMessage(err)));
  }, [orderId]);

  useEffect(() => {
    load();
  }, [load]);

  // Mirrors DisputeService.open: renter complaints only while IN_RENTAL and before handover is confirmed;
  // owner damage claims only after ENDED; an account takeover report works in both states.
  const choices = choicesFor(isRenter, isOwner, risks).filter((c) => {
    if (c.type === 'ACCOUNT_TAKEOVER') return orderStatus === 'IN_RENTAL' || orderStatus === 'ENDED';
    if (isRenter) return orderStatus === 'IN_RENTAL' && !handoverConfirmedAt;
    return orderStatus === 'ENDED';
  });
  const canOpen = choices.length > 0;
  const hasList = disputes && disputes.length > 0;
  if (!hasList && !canOpen && !loadError) return null;

  return (
    <section className="card stack">
      <h2>Khiếu nại</h2>
      {loadError && <p className="error">{loadError}</p>}
      {disputes?.map((d) => (
        <DisputeCard key={d.id} dispute={d} risks={risks} title={disputeTitle(d, risks)} canAddEvidence={isRenter || isOwner} onChanged={load} />
      ))}
      {canOpen && <OpenDisputeForm orderId={orderId} choices={choices} onOpened={load} />}
    </section>
  );
}

function DisputeCard({
  dispute: d,
  risks,
  title,
  canAddEvidence,
  onChanged,
}: {
  dispute: Dispute;
  risks: OrderRisk[];
  title: string;
  canAddEvidence: boolean;
  onChanged: () => void;
}) {
  const flash = useFlash();
  const [urls, setUrls] = useState('');
  const [note, setNote] = useState('');
  const [files, setFiles] = useState<File[]>([]);
  const [progress, setProgress] = useState<string | null>(null);
  const room = MAX_DISPUTE_FILES - d.evidence.filter(isAttachment).length;

  const onAdd = async (e: FormEvent) => {
    e.preventDefault();
    const links = parseUrls(urls);
    if (links.length === 0 && files.length === 0 && !note.trim()) {
      flash.setError('Hãy chọn ảnh/video, dán link hoặc viết ghi chú.');
      return;
    }
    let changed = false;
    const ok = await flash.run(async () => {
      // The note rides with the links, or with the first file when there are no links.
      if (links.length > 0 || files.length === 0) {
        await api(`/disputes/${d.id}/evidence`, { method: 'POST', body: { evidenceUrls: links, note: note || undefined } });
        changed = true;
        setUrls('');
      }
      if (files.length > 0) {
        await uploadDisputeFiles(d.id, files, links.length > 0 ? undefined : note || undefined, setProgress, () => {
          changed = true;
          setFiles((prev) => prev.slice(1)); // a retry after a failure only resends what is left
        });
      }
    }, 'Đã thêm bằng chứng.');
    setProgress(null);
    if (ok) setNote('');
    if (changed) onChanged();
  };

  return (
    <div className="card" style={{ marginBottom: 0 }}>
      <div className="row spread">
        <strong>{title}</strong>
        <span className={`badge ${d.status === 'OPEN' ? 'badge-warn' : d.status === 'RESOLVED' ? 'badge-ok' : 'badge-bad'}`}>
          {STATUS_LABEL[d.status] ?? d.status}
        </span>
      </div>
      {d.items && d.items.length > 0 && <DisputeItemList items={d.items} risks={risks} />}
      <p style={{ whiteSpace: 'pre-line' }}>{d.description}</p>
      <EvidenceList disputeId={d.id} evidence={d.evidence} />
      {d.deadlineAt && d.status === 'OPEN' && <p className="muted">Hạn phân xử: {formatDateTime(d.deadlineAt)}</p>}
      {d.status === 'OPEN' && d.items && d.items.length > 0 && (
        <p className="muted">Hỗ trợ viên xét từng rủi ro; khi xét xong, tiền hoàn và đền bù được tính và chuyển tự động.</p>
      )}
      {d.status !== 'OPEN' && (
        <dl className="kv" style={{ marginTop: '0.5rem' }}>
          <dt>Khấu trừ cọc</dt>
          <dd>
            {formatVnd(d.depositDeduction)}
            {d.depositDeduction > 0 && (
              <span className="muted">
                {' '}
                · phí VieGG {RISK_FEE_PERCENT}%: {formatVnd(riskFee(d.depositDeduction))} · chủ account nhận{' '}
                {formatVnd(riskNet(d.depositDeduction))}
              </span>
            )}
          </dd>
          <dt>Hoàn tiền thuê</dt>
          <dd>{formatVnd(d.rentRefund)}</dd>
          {d.compensation > 0 && (
            <>
              <dt>Đền bù từ chủ account</dt>
              <dd>
                {formatVnd(d.compensation)}
                <span className="muted">
                  {' '}
                  · phí VieGG {RISK_FEE_PERCENT}%: {formatVnd(riskFee(d.compensation))} · người thuê nhận{' '}
                  {formatVnd(riskNet(d.compensation))}
                </span>
              </dd>
            </>
          )}
          <dt>Lý do</dt>
          <dd>
            {labelOf(REASON, d.reasonCode)}
            {d.decisionNote ? ` — ${d.decisionNote}` : ''}
          </dd>
        </dl>
      )}
      {canAddEvidence && d.status === 'OPEN' && (
        <form className="form" style={{ marginTop: '0.75rem' }} onSubmit={onAdd}>
          <h4 style={{ margin: 0 }}>Thêm bằng chứng</h4>
          {room > 0 ? (
            <DisputeFilePicker id={`evf-${d.id}`} files={files} onChange={setFiles} room={room} disabled={flash.busy} />
          ) : (
            <p className="muted">Đã đủ {MAX_DISPUTE_FILES} ảnh/video cho khiếu nại này.</p>
          )}
          <div className="field">
            <label htmlFor={`ev-${d.id}`}>Hoặc dán link (mỗi dòng một link https)</label>
            <textarea id={`ev-${d.id}`} value={urls} onChange={(e) => setUrls(e.target.value)} />
          </div>
          <div className="field">
            <label htmlFor={`evn-${d.id}`}>Ghi chú</label>
            <input id={`evn-${d.id}`} maxLength={4000} value={note} onChange={(e) => setNote(e.target.value)} />
          </div>
          {progress && (
            <p className="muted" aria-live="polite">
              {progress}
            </p>
          )}
          <FlashMessages error={flash.error} notice={flash.notice} />
          <div>
            <button className="btn" disabled={flash.busy}>
              {flash.busy ? 'Đang gửi…' : 'Gửi bằng chứng'}
            </button>
          </div>
        </form>
      )}
    </div>
  );
}

function OpenDisputeForm({ orderId, choices, onOpened }: { orderId: string; choices: DisputeChoice[]; onOpened: () => void }) {
  const flash = useFlash();
  const [choiceKey, setChoiceKey] = useState(choices[0].key);
  const choice = choices.find((c) => c.key === choiceKey) ?? choices[0];
  // Renter risks: one complaint may list several of them.
  const multi = choices.every((c) => c.type === 'RENTER_RISK');
  const [picked, setPicked] = useState<string[]>([]);
  const toggle = (riskId: string) =>
    setPicked((prev) => (prev.includes(riskId) ? prev.filter((x) => x !== riskId) : [...prev, riskId]));
  const [description, setDescription] = useState('');
  const [urls, setUrls] = useState('');
  const [files, setFiles] = useState<File[]>([]);
  const [progress, setProgress] = useState<string | null>(null);

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault();
    if (multi && picked.length === 0) {
      flash.setError('Hãy chọn ít nhất một rủi ro.');
      return;
    }
    let opened = false;
    await flash.run(async () => {
      const body = multi
        ? {
            type: 'RENTER_RISK',
            // Keep the order of the list, not the order of clicks.
            riskIds: choices.filter((c) => c.riskId && picked.includes(c.riskId)).map((c) => c.riskId),
            description,
            evidenceUrls: parseUrls(urls),
          }
        : { type: choice.type, riskId: choice.riskId, description, evidenceUrls: parseUrls(urls) };
      const d = await api<Dispute>(`/orders/${orderId}/disputes`, { method: 'POST', body });
      opened = true;
      try {
        await uploadDisputeFiles(d.id, files, undefined, setProgress);
      } catch (err) {
        throw new Error(`Đã gửi khiếu nại nhưng tải tệp bị lỗi: ${errorMessage(err)} Hãy thêm lại ảnh/video ở khiếu nại phía trên.`);
      }
    }, 'Đã gửi khiếu nại.');
    setProgress(null);
    if (opened) {
      setDescription('');
      setUrls('');
      setFiles([]);
      setPicked([]);
      onOpened();
    }
  };

  return (
    <form className="form" onSubmit={onSubmit}>
      <h3>Tạo khiếu nại mới</h3>
      {multi ? (
        <fieldset className="field">
          <legend>Vấn đề gặp phải (chọn một hoặc nhiều)</legend>
          {choices.map((c) => (
            <label key={c.key} className="row" style={{ alignItems: 'flex-start', gap: '0.5rem', fontWeight: 'normal', flexWrap: 'nowrap' }}>
              <input
                type="checkbox"
                style={{ width: 'auto', marginTop: '0.25rem' }}
                checked={!!c.riskId && picked.includes(c.riskId)}
                onChange={() => c.riskId && toggle(c.riskId)}
              />
              <span>
                {c.label}
                {c.hint && (
                  <span className="field-hint" style={{ display: 'block' }}>
                    {c.hint}
                  </span>
                )}
              </span>
            </label>
          ))}
          <span className="field-hint">
            Hỗ trợ viên xét từng rủi ro. Nếu có ít nhất một rủi ro được chấp nhận, bạn được hoàn phần tiền thuê còn giữ và
            nhận đền bù của các rủi ro được chấp nhận (VieGG thu phí {RISK_FEE_PERCENT}%, bạn nhận {100 - RISK_FEE_PERCENT}%).
          </span>
        </fieldset>
      ) : (
      <div className="field">
        <label htmlFor="dp-type">Vấn đề gặp phải</label>
        <select id="dp-type" value={choice.key} onChange={(e) => setChoiceKey(e.target.value)} aria-describedby="dp-type-hint">
          {choices.map((c) => (
            <option key={c.key} value={c.key}>
              {c.label}
            </option>
          ))}
        </select>
        {choice.hint && (
          <span id="dp-type-hint" className="field-hint">
            {choice.hint}
          </span>
        )}
      </div>
      )}
      <div className="field">
        <label htmlFor="dp-desc">Mô tả</label>
        <textarea id="dp-desc" required maxLength={4000} value={description} onChange={(e) => setDescription(e.target.value)} />
      </div>
      <DisputeFilePicker id="dp-files" files={files} onChange={setFiles} disabled={flash.busy} />
      <div className="field">
        <label htmlFor="dp-urls">Hoặc dán link bằng chứng (mỗi dòng một link https, tối đa 20)</label>
        <textarea id="dp-urls" value={urls} onChange={(e) => setUrls(e.target.value)} />
      </div>
      {progress && (
        <p className="muted" aria-live="polite">
          {progress}
        </p>
      )}
      <FlashMessages error={flash.error} notice={flash.notice} />
      <div>
        <button className="btn btn-danger" disabled={flash.busy}>
          {flash.busy ? 'Đang gửi…' : 'Gửi khiếu nại'}
        </button>
      </div>
    </form>
  );
}
