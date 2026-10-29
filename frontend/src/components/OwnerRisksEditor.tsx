import type { GameRisk, GameView, Listing, OwnerRiskInput, RiskSide } from '../api/types';
import { riskInput, type RiskTermValues } from './RiskTermsFields';

/** Same limit as CatalogService.MAX_LISTING_RISKS. */
export const MAX_OWNER_RISKS = 10;

const NEW_PREFIX = 'new-';

/** The listing's own risks as editable drafts; new ones get a temporary id until they are saved. */
export function ownerRiskDrafts(listing?: Listing): GameRisk[] {
  return listing?.ownerRisks ?? [];
}

export function newOwnerRisk(side: RiskSide = 'OWNER'): GameRisk {
  const id = `${NEW_PREFIX}${Date.now().toString(36)}${Math.random().toString(36).slice(2, 8)}`;
  return { id, side, name: '', description: null, recommendation: null, complaintWindowMinutes: null, stackable: true, ownerDefined: true };
}

/** The game with the owner's own risks after its own: what the deposit and compensation fields work on. */
export function withOwnerRisks(game: GameView | undefined, drafts: GameRisk[]): GameView | undefined {
  if (!game || drafts.length === 0) return game;
  return { ...game, risks: [...game.risks, ...drafts.map((d) => ({ ...d, name: d.name.trim() || 'Rủi ro chưa đặt tên' }))] };
}

/** Body ownerRisks: the drafts with the amount the owner typed for each in the deposit / compensation fields. */
export function ownerRisksBody(drafts: GameRisk[], values: RiskTermValues, listing?: Listing): OwnerRiskInput[] {
  return drafts.map((d) => {
    const v = riskInput(values, d, listing);
    const amount = Number(v.amount) || 0;
    return {
      id: d.id.startsWith(NEW_PREFIX) ? null : d.id,
      side: d.side,
      name: d.name.trim(),
      description: d.description?.trim() || null,
      complaintWindowMinutes: d.complaintWindowMinutes,
      stackable: d.side !== 'OWNER' || d.stackable,
      amount: v.on && amount > 0 ? amount : null,
    };
  });
}

/** True when two drafts (or a draft and a game risk) have the same side and name, as the backend refuses it. */
export function duplicateOwnerRisk(game: GameView | undefined, drafts: GameRisk[]): string | null {
  const seen = new Set((game?.risks ?? []).map((r) => `${r.side}:${r.name.trim().toLowerCase()}`));
  for (const d of drafts) {
    const key = `${d.side}:${d.name.trim().toLowerCase()}`;
    if (d.name.trim() && seen.has(key)) return d.name.trim();
    seen.add(key);
  }
  return null;
}

/**
 * Lets the owner describe risks of their own account that the game's list does not cover (e.g. a rare skin). The
 * deposit / compensation for each is typed in the fields below, together with the game's risks.
 */
export function OwnerRisksEditor({
  game,
  drafts,
  onChange,
  onAdded,
  idPrefix,
}: {
  game: GameView;
  drafts: GameRisk[];
  onChange: (next: GameRisk[]) => void;
  /** Called with a new draft's id so its amount field starts switched on. */
  onAdded: (id: string) => void;
  idPrefix: string;
}) {
  const update = (id: string, patch: Partial<GameRisk>) => onChange(drafts.map((d) => (d.id === id ? { ...d, ...patch } : d)));
  const duplicate = duplicateOwnerRisk(game, drafts);
  const add = () => {
    const d = newOwnerRisk();
    onChange([...drafts, d]);
    onAdded(d.id);
  };

  return (
    <fieldset className="risk-fieldset">
      <legend>Rủi ro bạn tự thêm</legend>
      <p className="field-hint" style={{ margin: 0 }}>
        {game.risks.length > 0
          ? 'Ngoài các rủi ro VieGG đặt cho game, bạn có thể thêm rủi ro riêng của account này (vd. skin hiếm, vật phẩm giá trị).'
          : 'Game này chưa có rủi ro mẫu. Bạn có thể tự thêm rủi ro của account để tính tiền cọc / đền bù theo từng rủi ro.'}{' '}
        Nhập tiền cọc / đền bù cho từng rủi ro ở phần bên dưới. Tên và mô tả rủi ro được duyệt lại cùng bài đăng.
      </p>
      {drafts.map((d, i) => {
        const id = `${idPrefix}-own-${i}`;
        const hours = d.complaintWindowMinutes == null ? '' : String(d.complaintWindowMinutes / 60);
        return (
          <div key={d.id} className="owner-risk-row">
            <div className="field">
              <label htmlFor={`${id}-name`}>Tên rủi ro</label>
              <input
                id={`${id}-name`}
                required
                maxLength={120}
                placeholder="vd. Mất skin Rồng Thần"
                value={d.name}
                onChange={(e) => update(d.id, { name: e.target.value })}
              />
            </div>
            <div className="field">
              <label htmlFor={`${id}-side`}>Ai chịu thiệt</label>
              <select id={`${id}-side`} value={d.side} onChange={(e) => update(d.id, { side: e.target.value as RiskSide })}>
                <option value="OWNER">Bạn (người thuê đặt cọc)</option>
                <option value="RENTER">Người thuê (bạn đền bù)</option>
              </select>
            </div>
            <div className="field">
              <label htmlFor={`${id}-window`}>Hạn khiếu nại (giờ)</label>
              <input
                id={`${id}-window`}
                type="number"
                min={1}
                max={720}
                step={1}
                placeholder="Không giới hạn"
                value={hours}
                onChange={(e) => {
                  const h = Number(e.target.value);
                  update(d.id, { complaintWindowMinutes: e.target.value === '' || !(h > 0) ? null : Math.round(h * 60) });
                }}
              />
            </div>
            <div className="field owner-risk-desc">
              <label htmlFor={`${id}-desc`}>Mô tả (không bắt buộc)</label>
              <input
                id={`${id}-desc`}
                maxLength={1000}
                value={d.description ?? ''}
                onChange={(e) => update(d.id, { description: e.target.value })}
              />
            </div>
            <div className="owner-risk-actions">
              {d.side === 'OWNER' && (
                <label className="checkbox">
                  <input type="checkbox" checked={d.stackable} onChange={(e) => update(d.id, { stackable: e.target.checked })} />
                  Cộng dồn
                </label>
              )}
              <button type="button" className="btn btn-ghost" onClick={() => onChange(drafts.filter((x) => x.id !== d.id))}>
                Xoá
              </button>
            </div>
          </div>
        );
      })}
      {duplicate && <p className="field-error">Rủi ro “{duplicate}” bị trùng tên với rủi ro khác cùng loại.</p>}
      <div>
        <button type="button" className="btn" disabled={drafts.length >= MAX_OWNER_RISKS} onClick={add}>
          + Thêm rủi ro
        </button>
        {drafts.length >= MAX_OWNER_RISKS && <span className="field-hint"> Tối đa {MAX_OWNER_RISKS} rủi ro.</span>}
      </div>
    </fieldset>
  );
}
