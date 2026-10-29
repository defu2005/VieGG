import { Link } from 'react-router-dom';
import { formatDateTime, formatVnd } from '../lib/format';
import { CopyId } from './CopyId';
import { UserRef } from './UserRef';
import { ACCOUNT_CODE, FIELD, labelOf, ORDER_STATUS, TOS_STATUS, type LabelDict } from '../lib/labels';

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const ISO_DATE = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/;
const MONEY_KEYS = /amount|escrow|held|deduct|refund|payable|price|deposit|sum/i;

/** Dictionaries used to translate values of known keys. */
const VALUE_DICTS: Record<string, LabelDict> = {
  accountCode: ACCOUNT_CODE,
  account: ACCOUNT_CODE,
  tosStatus: TOS_STATUS,
  status: ORDER_STATUS,
};

/** Links for IDs whose detail page exists. */
function idLink(key: string, id: string) {
  const short = id.slice(0, 8);
  if (/order/i.test(key))
    return (
      <span className="row" style={{ gap: '0.4rem', flexWrap: 'nowrap' }}>
        <Link to={`/orders/${id}`}>Đơn {short}</Link>
        <CopyId id={id} label="copy" />
      </span>
    );
  if (/owner|user|renter|openedBy|decidedBy|actor/i.test(key)) return <UserRef id={id} showEmail={false} />;
  return (
    <span className="secret" title={id}>
      {short}
    </span>
  );
}

/** Known names for IDs (e.g. game id → game name), so pages can show names instead of codes. */
type Names = Record<string, string>;

function renderValue(key: string, value: unknown, names: Names = {}) {
  if (value == null || value === '') return '—';
  if (typeof value === 'boolean') return value ? 'Có' : 'Không';
  if (typeof value === 'number') return MONEY_KEYS.test(key) ? formatVnd(value) : value.toLocaleString('vi-VN');
  if (typeof value === 'string') {
    if (names[value]) return names[value];
    if (UUID.test(value)) return idLink(key, value);
    if (ISO_DATE.test(value)) return formatDateTime(value);
    const dict = VALUE_DICTS[key];
    return dict ? labelOf(dict, value) : value;
  }
  if (Array.isArray(value)) {
    if (value.length === 0) return 'Không có';
    if (value.every((v) => v && typeof v === 'object' && !Array.isArray(v))) return <DataTable rows={value as Record<string, unknown>[]} />;
    return value.map((v) => String(v)).join(', ');
  }
  if (typeof value === 'object') return <DataList data={value as Record<string, unknown>} />;
  return String(value);
}

/** A record shown as label/value pairs, replacing raw JSON dumps. */
export function DataList({ data, names }: { data: Record<string, unknown>; names?: Names }) {
  const entries = Object.entries(data);
  if (entries.length === 0) return <p className="muted">Không có dữ liệu.</p>;
  return (
    <dl className="kv">
      {entries.map(([k, v]) => (
        <div key={k} style={{ display: 'contents' }}>
          <dt>{labelOf(FIELD, k)}</dt>
          <dd>{renderValue(k, v, names)}</dd>
        </div>
      ))}
    </dl>
  );
}

/** A list of records shown as a table with translated column names. */
export function DataTable({ rows }: { rows: Record<string, unknown>[] }) {
  if (rows.length === 0) return <p className="muted">Không có dòng nào.</p>;
  const cols = Array.from(new Set(rows.flatMap((r) => Object.keys(r))));
  return (
    <div style={{ overflowX: 'auto' }}>
      <table>
        <thead>
          <tr>
            {cols.map((c) => (
              <th key={c}>{labelOf(FIELD, c)}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((r, i) => (
            <tr key={i}>
              {cols.map((c) => (
                <td key={c}>{renderValue(c, r[c])}</td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
