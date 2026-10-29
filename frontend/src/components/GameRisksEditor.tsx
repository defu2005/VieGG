import type { GameRisk, GameRiskInput, RiskSide } from '../api/types';
import { formatVnd } from '../lib/format';
import { EXCLUSIVE_LABEL, STACKABLE_LABEL } from '../lib/riskDeposit';

export const RISK_SIDE_LABEL: Record<RiskSide, string> = {
  OWNER: 'Rủi ro của chủ account',
  RENTER: 'Rủi ro của người thuê',
};

const SIDE_HELP: Record<RiskSide, string> = {
  OWNER:
    'Người thuê làm hại account. Chủ account đặt tiền cọc cho từng rủi ro. Rủi ro "cộng dồn" (tụt rank, tụt uy tín…) có thể xảy ra cùng lúc nên được cộng lại; rủi ro "không cộng dồn" (bị nhà phát hành cấm vĩnh viễn…) xảy ra thì người thuê chỉ trả cọc của rủi ro đó. Cọc của đơn = max(tổng cộng dồn, khoản không cộng dồn lớn nhất).',
  RENTER:
    'Người thuê không dùng được account như đã trả tiền (sai mật khẩu, không đúng mô tả…). Chủ account có thể cam kết đền bù; không đặt thì người thuê chỉ được hoàn tiền thuê.',
};

/** Starting point for a game without risks yet. */
export const SUGGESTED_RISKS: GameRiskInput[] = [
  { id: null, side: 'OWNER', name: 'Tụt rank', description: 'Người thuê đánh thua nhiều làm tụt hạng.', recommendation: 'Khoảng 20.000 đ cho mỗi bậc rank có thể bị tụt trong thời gian thuê.', complaintWindowMinutes: 1440, stackable: true },
  { id: null, side: 'OWNER', name: 'Tụt điểm uy tín', description: 'Treo máy, bỏ trận, bị báo cáo làm giảm uy tín.', recommendation: '30.000 – 50.000 đ, đủ để bạn cày lại điểm uy tín.', complaintWindowMinutes: 1440, stackable: true },
  { id: null, side: 'OWNER', name: 'Mất hoặc bị bán vật phẩm, tiền trong game', description: null, recommendation: 'Bằng giá trị vật phẩm hoặc số tiền trong game đắt nhất mà người thuê có thể bán/tiêu.', complaintWindowMinutes: 1440, stackable: true },
  { id: null, side: 'OWNER', name: 'Account bị nhà phát hành cấm vĩnh viễn', description: 'Mất hẳn account: người thuê chỉ trả khoản cọc này.', recommendation: 'Bằng giá trị account (giá bạn có thể bán account), và không thấp hơn tổng các khoản cộng dồn.', complaintWindowMinutes: 4320, stackable: false },
  { id: null, side: 'RENTER', name: 'Sai tài khoản hoặc mật khẩu', description: null, recommendation: 'Không bắt buộc. Một khoản nhỏ (10–20% tiền thuê) giúp người thuê yên tâm hơn.', complaintWindowMinutes: 15, stackable: true },
  { id: null, side: 'RENTER', name: 'Account không đúng mô tả', description: null, recommendation: 'Không bắt buộc. Nên đền bù nếu mô tả có rank, tướng, trang phục cụ thể.', complaintWindowMinutes: 15, stackable: true },
];

export function toRiskInputs(risks: GameRisk[] | undefined): GameRiskInput[] {
  return (risks ?? []).map((r) => ({ ...r, stackable: r.stackable ?? true }));
}

const MAX_RECOMMENDATION = 500;

/** Editable list of a game's risks, grouped by side. */
export function GameRisksEditor({
  risks,
  onChange,
  idPrefix,
  depositMin,
  depositMax,
}: {
  risks: GameRiskInput[];
  onChange: (next: GameRiskInput[]) => void;
  idPrefix: string;
  depositMin?: number;
  depositMax?: number;
}) {
  const update = (index: number, patch: Partial<GameRiskInput>) =>
    onChange(risks.map((r, i) => (i === index ? { ...r, ...patch } : r)));
  const remove = (index: number) => onChange(risks.filter((_, i) => i !== index));
  const add = (side: RiskSide) =>
    onChange([
      ...risks,
      {
        id: null,
        side,
        name: '',
        description: null,
        recommendation: null,
        complaintWindowMinutes: side === 'RENTER' ? 15 : null,
        stackable: true,
      },
    ]);

  const hasOwnerRisks = risks.some((r) => r.side === 'OWNER');

  return (
    <fieldset className="risk-fieldset">
      <legend>Rủi ro khi cho thuê</legend>
      {risks.length === 0 && (
        <div className="row">
          <span className="muted">Game chưa có rủi ro nào.</span>
          <button type="button" className="btn" onClick={() => onChange(SUGGESTED_RISKS.map((r) => ({ ...r })))}>
            Dùng bộ rủi ro gợi ý
          </button>
        </div>
      )}
      {(['OWNER', 'RENTER'] as RiskSide[]).map((side) => (
        <div key={side} className="stack">
          <div>
            <strong>{RISK_SIDE_LABEL[side]}</strong>
            <p className="field-hint" style={{ margin: 0 }}>{SIDE_HELP[side]}</p>
          </div>
          {risks.map((r, i) =>
            r.side !== side ? null : (
              <div key={i} className="risk-row">
                <div className="field">
                  <label htmlFor={`${idPrefix}-risk-name-${i}`}>Tên rủi ro</label>
                  <input
                    id={`${idPrefix}-risk-name-${i}`}
                    required
                    maxLength={120}
                    value={r.name}
                    onChange={(e) => update(i, { name: e.target.value })}
                  />
                  {side === 'OWNER' && (
                    <select
                      aria-label={`Kiểu cọc của rủi ro ${r.name || 'mới'}`}
                      value={r.stackable ? 'stack' : 'exclusive'}
                      onChange={(e) => update(i, { stackable: e.target.value === 'stack' })}
                    >
                      <option value="stack">{STACKABLE_LABEL} — cộng với các rủi ro khác</option>
                      <option value="exclusive">{EXCLUSIVE_LABEL} — chỉ trả khoản này</option>
                    </select>
                  )}
                </div>
                <div className="field">
                  <label htmlFor={`${idPrefix}-risk-rec-${i}`}>
                    {side === 'OWNER' ? 'Khuyến nghị cọc' : 'Khuyến nghị đền bù'}
                  </label>
                  <textarea
                    id={`${idPrefix}-risk-rec-${i}`}
                    rows={2}
                    maxLength={MAX_RECOMMENDATION}
                    placeholder={side === 'OWNER' ? 'VD: Bằng giá trị account' : 'VD: Không bắt buộc, 10–20% tiền thuê'}
                    value={r.recommendation ?? ''}
                    onChange={(e) => update(i, { recommendation: e.target.value || null })}
                  />
                  <span className="field-hint">Owner thấy khi đặt tiền cọc</span>
                </div>
                <div className="field">
                  <label htmlFor={`${idPrefix}-risk-window-${i}`}>Hạn khiếu nại (phút)</label>
                  <input
                    id={`${idPrefix}-risk-window-${i}`}
                    type="number"
                    min={1}
                    max={43200}
                    placeholder="Không giới hạn"
                    value={r.complaintWindowMinutes ?? ''}
                    onChange={(e) => update(i, { complaintWindowMinutes: e.target.value === '' ? null : Number(e.target.value) })}
                  />
                  <span className="field-hint">
                    {side === 'RENTER' ? 'Tính từ lúc người thuê xem thông tin tài khoản' : 'Tính từ lúc kết thúc thuê'}
                  </span>
                </div>
                <div className="field risk-desc">
                  <label htmlFor={`${idPrefix}-risk-desc-${i}`}>Mô tả (không bắt buộc)</label>
                  <input
                    id={`${idPrefix}-risk-desc-${i}`}
                    maxLength={1000}
                    value={r.description ?? ''}
                    onChange={(e) => update(i, { description: e.target.value || null })}
                  />
                </div>
                <button type="button" className="btn btn-sm" onClick={() => remove(i)} aria-label={`Xóa rủi ro ${r.name || 'mới'}`}>
                  Xóa
                </button>
              </div>
            ),
          )}
          <div>
            <button type="button" className="btn btn-sm" onClick={() => add(side)}>
              + Thêm {side === 'OWNER' ? 'rủi ro của chủ account' : 'rủi ro của người thuê'}
            </button>
          </div>
        </div>
      ))}
      {hasOwnerRisks && (
        <p className="field-hint" style={{ margin: 0 }}>
          Cọc của đơn = max(tổng các khoản cộng dồn, khoản không cộng dồn lớn nhất) do owner đặt
          {depositMin != null && depositMax != null && `, phải nằm trong ${formatVnd(depositMin)} – ${formatVnd(depositMax)}`}.
        </p>
      )}
    </fieldset>
  );
}
