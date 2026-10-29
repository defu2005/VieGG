import type { GameRisk, GameView, Listing, RiskTerm } from '../api/types';
import { formatVnd } from '../lib/format';
import { RISK_FEE_PERCENT } from '../lib/riskFee';
import { EXCLUSIVE_LABEL, riskDeposit, STACKABLE_LABEL, type RiskDepositBreakdown } from '../lib/riskDeposit';

/** What the owner chose for one risk: whether it is compensated ({@code on}) and the amount typed ('' = nothing). */
export interface RiskTermInput {
  on: boolean;
  amount: string;
}

/** Owner's choices per risk id; a risk missing here keeps its starting value. */
export type RiskTermValues = Record<string, RiskTermInput>;

/**
 * Starting value of a risk: a new listing always suggests compensating ({@code on}); an existing listing shows what
 * it has. Listings made before risks existed have no terms at all: their owner risks start on (to be filled in).
 */
export function initialRiskInput(risk: GameRisk, listing?: Listing): RiskTermInput {
  if (!listing) return { on: true, amount: '' };
  const terms = listing.riskTerms ?? [];
  const term = terms.find((t) => t.riskId === risk.id);
  if (term?.amount != null) return { on: true, amount: String(term.amount) };
  return { on: risk.side === 'OWNER' && terms.length === 0, amount: '' };
}

export function riskInput(values: RiskTermValues, risk: GameRisk, listing?: Listing): RiskTermInput {
  return values[risk.id] ?? initialRiskInput(risk, listing);
}

const num = (s: string) => Number(s) || 0;

/** Amount that counts for a risk: 0 when the owner chose no compensation. */
function amountOf(values: RiskTermValues, r: GameRisk, listing?: Listing): number {
  const v = riskInput(values, r, listing);
  return v.on ? num(v.amount) : 0;
}

/** Body riskTerms: one entry per compensated risk with an amount > 0. */
export function riskTermsOf(game: GameView | undefined, values: RiskTermValues, listing?: Listing): RiskTerm[] {
  if (!game) return [];
  return game.risks.map((r) => ({ riskId: r.id, amount: amountOf(values, r, listing) })).filter((t) => t.amount > 0);
}

/** Deposit breakdown of the owner risks; null when the game has none (single deposit field instead). */
export function riskDepositOf(game: GameView | undefined, values: RiskTermValues, listing?: Listing): RiskDepositBreakdown | null {
  const owner = game?.risks.filter((r) => r.side === 'OWNER') ?? [];
  if (owner.length === 0) return null;
  return riskDeposit(owner.map((r) => ({ amount: amountOf(values, r, listing), stackable: r.stackable ?? true })));
}

export function riskDepositTotal(game: GameView | undefined, values: RiskTermValues, listing?: Listing): number | null {
  return riskDepositOf(game, values, listing)?.deposit ?? null;
}

/** Risks switched to "compensate" but without an amount yet. */
export function missingRiskAmounts(game: GameView | undefined, values: RiskTermValues, listing?: Listing): GameRisk[] {
  return (game?.risks ?? []).filter((r) => {
    const v = riskInput(values, r, listing);
    return v.on && !(num(v.amount) > 0);
  });
}

/** True when the game has owner risks but the owner covers none of them (an order always needs a deposit). */
export function noOwnerRiskCovered(game: GameView | undefined, values: RiskTermValues, listing?: Listing): boolean {
  const owner = game?.risks.filter((r) => r.side === 'OWNER') ?? [];
  return owner.length > 0 && owner.every((r) => !riskInput(values, r, listing).on);
}

function windowText(r: GameRisk): string | null {
  if (r.complaintWindowMinutes == null) return null;
  const m = r.complaintWindowMinutes;
  const t = m % 1440 === 0 ? `${m / 1440} ngày` : m % 60 === 0 ? `${m / 60} giờ` : `${m} phút`;
  return r.side === 'RENTER'
    ? `Người thuê phải khiếu nại trong ${t} sau khi xem thông tin tài khoản.`
    : `Bạn phải khiếu nại trong ${t} sau khi kết thúc thuê.`;
}

/** Advice for the owner, computed from what they chose. */
function suggestionsFor(game: GameView, values: RiskTermValues, b: RiskDepositBreakdown, listing?: Listing): string[] {
  const out: string[] = [];
  const owner = game.risks.filter((r) => r.side === 'OWNER');
  const exclusive = owner.filter((r) => !(r.stackable ?? true));
  const stackable = owner.filter((r) => r.stackable ?? true);
  const missing = missingRiskAmounts(game, values, listing).filter((r) => r.side === 'OWNER');
  const skipped = owner.filter((r) => !riskInput(values, r, listing).on);

  if (noOwnerRiskCovered(game, values, listing)) {
    out.push(`Game yêu cầu tiền cọc tối thiểu ${formatVnd(game.depositMin)}, nên cần bật đền bù cho ít nhất một rủi ro.`);
  } else if (missing.length > 0) {
    out.push(`Chưa nhập tiền cọc cho: ${missing.map((r) => r.name).join(', ')}. Xem khuyến nghị của VieGG dưới mỗi rủi ro.`);
  } else if (b.deposit < game.depositMin) {
    out.push(`Cọc của đơn đang thấp hơn mức tối thiểu của game (${formatVnd(game.depositMin)}). Hãy tăng một vài khoản.`);
  } else if (b.deposit > game.depositMax) {
    out.push(
      `Cọc của đơn vượt mức tối đa của game (${formatVnd(game.depositMax)}). Hãy giảm ${
        b.drivenBy === 'exclusive' ? 'khoản không cộng dồn lớn nhất' : 'các khoản cộng dồn'
      }.`,
    );
  }
  if (skipped.length > 0) {
    out.push(
      `Bạn đang không yêu cầu đền bù cho: ${skipped.map((r) => r.name).join(', ')}. Nếu sự cố này xảy ra, bạn sẽ không thể khiếu nại khấu trừ cọc. VieGG khuyên nên bật.`,
    );
  }
  if (exclusive.length > 0 && stackable.length > 0 && b.exclusiveMax > 0 && b.exclusiveMax < b.stackedSum) {
    out.push(
      `Khoản không cộng dồn lớn nhất (${formatVnd(b.exclusiveMax)}) đang thấp hơn tổng cộng dồn (${formatVnd(b.stackedSum)}): nếu account bị cấm vĩnh viễn, người thuê sẽ trả ít hơn so với khi chỉ làm tụt rank. Nên đặt khoản đó ít nhất ${formatVnd(b.stackedSum)}, thường bằng giá trị account.`,
    );
  }
  if (b.drivenBy === 'exclusive' && stackable.some((r) => riskInput(values, r, listing).on)) {
    out.push(
      `Cọc của đơn đang do khoản không cộng dồn quyết định (${formatVnd(b.exclusiveMax)}). Bạn có thể tăng các khoản cộng dồn tới tổng ${formatVnd(b.exclusiveMax)} mà người thuê không phải trả thêm cọc.`,
    );
  }
  if (missing.length === 0 && b.deposit > 0) {
    out.push('Nếu sự cố xảy ra, bạn chỉ được khấu trừ tối đa bằng số đã đặt cho rủi ro đó. Cọc quá cao có thể làm người thuê ngại đặt.');
  }
  const renter = game.risks.filter((r) => r.side === 'RENTER');
  if (renter.length > 0 && renter.every((r) => !riskInput(values, r, listing).on)) {
    out.push('Cam kết một khoản đền bù nhỏ cho "sai mật khẩu" hoặc "không đúng mô tả" giúp người thuê yên tâm đặt hơn.');
  }
  return out;
}

/**
 * Owner chooses, per risk, whether it is compensated (suggested: yes) and how much: a deposit share for an owner
 * risk, a compensation for a renter risk.
 */
export function RiskTermsFields({
  game,
  values,
  onChange,
  listing,
  idPrefix,
}: {
  game: GameView;
  values: RiskTermValues;
  onChange: (next: RiskTermValues) => void;
  listing?: Listing;
  idPrefix: string;
}) {
  const owner = game.risks.filter((r) => r.side === 'OWNER');
  const stackable = owner.filter((r) => r.stackable ?? true);
  const exclusive = owner.filter((r) => !(r.stackable ?? true));
  const renter = game.risks.filter((r) => r.side === 'RENTER');
  const b = riskDepositOf(game, values, listing);
  const noneCovered = noOwnerRiskCovered(game, values, listing);
  const outOfRange = b != null && (noneCovered || b.deposit < game.depositMin || b.deposit > game.depositMax);
  const tips = b ? suggestionsFor(game, values, b, listing) : [];
  const renterTips = !b ? suggestionsFor(game, values, { stackedSum: 0, exclusiveMax: 0, deposit: 0, drivenBy: 'none' }, listing).slice(-1) : [];

  const set = (r: GameRisk, patch: Partial<RiskTermInput>) =>
    onChange({ ...values, [r.id]: { ...riskInput(values, r, listing), ...patch } });

  const row = (r: GameRisk) => {
    const id = `${idPrefix}-risk-${r.id}`;
    const v = riskInput(values, r, listing);
    const missing = v.on && !(num(v.amount) > 0);
    const hint = windowText(r);
    return (
      <div key={r.id} className={`risk-term${v.on ? '' : ' is-off'}`}>
        <div>
          <strong>{r.name}</strong>
          {r.ownerDefined && <span className="tag" style={{ marginLeft: '0.4rem' }}>Bạn tự thêm</span>}
          {r.description && <div className="field-hint">{r.description}</div>}
          {r.recommendation && (
            <div className="risk-recommendation">
              💡 Khuyến nghị {r.side === 'OWNER' ? 'cọc' : 'đền bù'}: {r.recommendation}
            </div>
          )}
          {hint && v.on && <div className="field-hint">{hint}</div>}
        </div>
        <div className="risk-term-control">
          <label className="checkbox">
            <input type="checkbox" checked={v.on} onChange={(e) => set(r, { on: e.target.checked })} />
            Cần đền bù <span className="field-hint">(gợi ý: có)</span>
          </label>
          {v.on ? (
            <input
              id={id}
              type="number"
              min={0}
              step={1000}
              required
              aria-label={`${r.side === 'OWNER' ? 'Tiền cọc' : 'Tiền đền bù'} cho "${r.name}" (đ)`}
              placeholder={r.side === 'OWNER' ? 'Tiền cọc (đ)' : 'Tiền đền bù (đ)'}
              aria-invalid={missing || undefined}
              value={v.amount}
              onChange={(e) => set(r, { amount: e.target.value })}
            />
          ) : (
            <span className="field-hint">
              {r.side === 'OWNER' ? 'Không đền bù: bạn không thể khiếu nại khấu trừ cọc cho rủi ro này.' : 'Không đền bù: người thuê chỉ được hoàn tiền thuê.'}
            </span>
          )}
        </div>
      </div>
    );
  };

  return (
    <div className="stack">
      {owner.length > 0 && b && (
        <fieldset className="risk-fieldset">
          <legend>Đền bù cho rủi ro của bạn (chủ account)</legend>
          <p className="field-hint" style={{ margin: 0 }}>
            Với mỗi rủi ro, chọn có cần người thuê đền bù không (VieGG gợi ý nên có). Người thuê trả cọc; nếu sự cố xảy ra, bạn khiếu nại và
            được khấu trừ tối đa bằng khoản cọc của rủi ro đó. VieGG thu phí {RISK_FEE_PERCENT}% trên khoản khấu trừ: bạn nhận{' '}
            {100 - RISK_FEE_PERCENT}%.
          </p>
          {stackable.length > 0 && (
            <>
              <strong>
                {STACKABLE_LABEL} <span className="field-hint">— có thể xảy ra cùng lúc, khấu trừ được cộng lại</span>
              </strong>
              <div className="risk-terms">{stackable.map(row)}</div>
            </>
          )}
          {exclusive.length > 0 && (
            <>
              <strong>
                {EXCLUSIVE_LABEL} <span className="field-hint">— nếu xảy ra, người thuê chỉ trả khoản cọc của rủi ro này</span>
              </strong>
              <div className="risk-terms">{exclusive.map(row)}</div>
            </>
          )}
          <div className={`risk-panel ${outOfRange ? 'tone-bad' : 'tone-ok'}`}>
            <p>
              Tiền cọc của đơn = mức cao nhất người thuê có thể phải trả = max(tổng cộng dồn{' '}
              <strong>{formatVnd(b.stackedSum)}</strong>
              {exclusive.length > 0 && (
                <>
                  , không cộng dồn lớn nhất <strong>{formatVnd(b.exclusiveMax)}</strong>
                </>
              )}
              ) = <strong>{formatVnd(b.deposit)}</strong>
            </p>
            <p className="field-hint">
              Người thuê thanh toán: tiền thuê + {formatVnd(b.deposit)} tiền cọc. Khoảng cho phép của game: {formatVnd(game.depositMin)} –{' '}
              {formatVnd(game.depositMax)}.
            </p>
          </div>
          {tips.length > 0 && (
            <div className="risk-panel tone-warn" aria-live="polite">
              <strong>Gợi ý</strong>
              <ul className="risk-list">
                {tips.map((t) => (
                  <li key={t}>{t}</li>
                ))}
              </ul>
            </div>
          )}
        </fieldset>
      )}
      {renter.length > 0 && (
        <fieldset className="risk-fieldset">
          <legend>Đền bù cho rủi ro của người thuê</legend>
          <p className="field-hint" style={{ margin: 0 }}>
            Với mỗi rủi ro, chọn có đền bù cho người thuê không (VieGG gợi ý nên có). Khi khiếu nại đúng, người thuê luôn được hoàn tiền thuê;
            khoản đền bù (nếu có) được trừ đủ từ doanh thu của đơn hoặc ví của bạn. VieGG thu phí {RISK_FEE_PERCENT}% trên khoản đền bù:
            người thuê nhận {100 - RISK_FEE_PERCENT}%.
          </p>
          <div className="risk-terms">{renter.map(row)}</div>
          {renterTips.map((t) => (
            <p key={t} className="field-hint">
              {t}
            </p>
          ))}
        </fieldset>
      )}
    </div>
  );
}
