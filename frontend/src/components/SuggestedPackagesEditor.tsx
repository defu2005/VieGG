import type { SuggestedPackage } from '../api/types';
import { durationLabel, PACKAGE_DURATIONS } from '../lib/pricing';

const MAX_PACKAGES = 10;

/** Body packages: sorted by duration, a blank or 0 price = only the duration is suggested. */
export function cleanPackages(list: SuggestedPackage[]): SuggestedPackage[] {
  return [...list]
    .map((p) => ({ durationMinutes: p.durationMinutes, price: p.price && p.price > 0 ? p.price : null }))
    .sort((a, b) => a.durationMinutes - b.durationMinutes);
}

/**
 * Staff edit the rental packages suggested to owners of a game. Owners still choose which ones to offer and can add
 * their own; existing listings are not changed.
 */
export function SuggestedPackagesEditor({
  packages,
  onChange,
  idPrefix,
  minDurationMinutes,
  maxDurationMinutes,
}: {
  packages: SuggestedPackage[];
  onChange: (next: SuggestedPackage[]) => void;
  idPrefix: string;
  minDurationMinutes: number;
  maxDurationMinutes: number;
}) {
  const choices = PACKAGE_DURATIONS.filter((d) => d >= minDurationMinutes && d <= maxDurationMinutes);
  const durations = packages.map((p) => p.durationMinutes);
  const duplicate = new Set(durations).size !== durations.length;
  const outOfRange = packages.some((p) => p.durationMinutes < minDurationMinutes || p.durationMinutes > maxDurationMinutes);
  const update = (i: number, patch: Partial<SuggestedPackage>) =>
    onChange(packages.map((p, j) => (j === i ? { ...p, ...patch } : p)));
  const add = () => {
    const next = choices.find((d) => !durations.includes(d)) ?? choices[0] ?? minDurationMinutes;
    onChange([...packages, { durationMinutes: next, price: null }]);
  };

  return (
    <fieldset className="stack" style={{ border: '1px solid var(--border, #ddd)', borderRadius: 8, padding: '0.75rem' }}>
      <legend>Gói thuê gợi ý cho owner</legend>
      <p className="muted" style={{ margin: 0 }}>
        Owner thấy các gói này khi tạo listing và tự chọn gói nào sẽ cho thuê; họ vẫn có thể thêm gói riêng. Listing đã có không bị đổi.
      </p>
      {packages.length === 0 && <p className="field-hint">Chưa có gói gợi ý.</p>}
      {packages.map((p, i) => (
        <div className="row" key={i}>
          <select
            id={`${idPrefix}-pkg-${i}`}
            aria-label={`Thời lượng gói gợi ý ${i + 1}`}
            value={p.durationMinutes}
            onChange={(e) => update(i, { durationMinutes: Number(e.target.value) })}
          >
            {(choices.includes(p.durationMinutes) ? choices : [p.durationMinutes, ...choices]).map((d) => (
              <option key={d} value={d}>
                {durationLabel(d)}
              </option>
            ))}
          </select>
          <input
            aria-label={`Giá gợi ý gói ${i + 1} (đ)`}
            type="number"
            min={0}
            step={1000}
            placeholder="Giá gợi ý (không bắt buộc)"
            value={p.price ?? ''}
            onChange={(e) => update(i, { price: e.target.value === '' ? null : Number(e.target.value) })}
          />
          <button type="button" className="btn" onClick={() => onChange(packages.filter((_, j) => j !== i))}>
            Xóa
          </button>
        </div>
      ))}
      <div>
        <button type="button" className="btn" disabled={packages.length >= MAX_PACKAGES || choices.length === 0} onClick={add}>
          + Thêm gói gợi ý
        </button>
      </div>
      {(duplicate || outOfRange) && (
        <span className="field-error">
          {duplicate ? 'Mỗi gói gợi ý phải có thời lượng khác nhau.' : 'Có gói nằm ngoài thời lượng thuê cho phép của game.'}
        </span>
      )}
    </fieldset>
  );
}
