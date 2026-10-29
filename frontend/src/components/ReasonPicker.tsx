import { useState } from 'react';
import { REASON } from '../lib/labels';

const OTHER = '__other__';

/**
 * Lets staff pick a reason sentence instead of typing a code. "Khác" opens a free-text box;
 * the typed text is sent as-is. `value` is the reason sent to the API ('' when none chosen).
 */
export function ReasonPicker({
  id,
  value,
  onChange,
  options = Object.keys(REASON),
  label = 'Lý do',
}: {
  id: string;
  value: string;
  onChange: (v: string) => void;
  options?: string[];
  label?: string;
}) {
  const [custom, setCustom] = useState(value !== '' && !options.includes(value));

  return (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      <select
        id={id}
        value={custom ? OTHER : value}
        onChange={(e) => {
          const v = e.target.value;
          setCustom(v === OTHER);
          onChange(v === OTHER ? '' : v);
        }}
      >
        <option value="">Chọn lý do…</option>
        {options.map((code) => (
          <option key={code} value={code}>
            {REASON[code]?.label ?? code}
          </option>
        ))}
        <option value={OTHER}>Khác (tự nhập)…</option>
      </select>
      {custom && (
        <input aria-label={`${label} (tự nhập)`} placeholder="Nhập lý do" maxLength={200} value={value} onChange={(e) => onChange(e.target.value)} />
      )}
    </div>
  );
}
