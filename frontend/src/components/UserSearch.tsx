import { useEffect, useRef, useState, type FormEvent } from 'react';
import { api } from '../api/client';
import type { PageResponse, RoleDef, UserHit, UserMatch } from '../api/types';
import { errorMessage } from '../lib/format';
import { labelOf, RISK_TIER, ROLE, USER_STATUS } from '../lib/labels';
import { StatusBadge } from './StatusBadge';

const MATCH_LABEL: Record<UserMatch, string> = {
  USER_ID: 'mã người dùng',
  EMAIL: 'email',
  NAME: 'tên',
  ORDER: 'mã đơn',
  LISTING: 'mã tin đăng',
  WITHDRAWAL: 'mã lệnh rút',
};

export interface UserFilters {
  status?: string;
  /** Not shown in the UI: KYC is not used yet; the API already accepts it for when it is. */
  kycStatus?: string;
  riskTier?: string;
  role?: string;
}

/**
 * Staff user finder shared by every screen that needs to pick a user. One box takes email, name (accents
 * optional), user id, or an order / listing / withdrawal id — full or the first characters shown on screen.
 */
export function UserSearch({
  onPick,
  pickLabel = 'Chọn',
  withFilters = false,
  initialFilters = {},
  listWhenEmpty = false,
  placeholder = 'Email, tên, mã người dùng, mã đơn hoặc mã tin đăng',
  excludeIds = [],
}: {
  onPick: (u: UserHit) => void;
  pickLabel?: string;
  /** Show status / KYC / risk / role filters (the Users page). */
  withFilters?: boolean;
  initialFilters?: UserFilters;
  /** Show newest users before anything is typed. */
  listWhenEmpty?: boolean;
  placeholder?: string;
  excludeIds?: string[];
}) {
  const [q, setQ] = useState('');
  const [filters, setFilters] = useState<UserFilters>(initialFilters);
  const [page, setPage] = useState(0);
  const [result, setResult] = useState<PageResponse<UserHit> | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [roles, setRoles] = useState<RoleDef[]>([]);
  const seq = useRef(0);

  const hasFilter = Object.values(filters).some(Boolean);
  const active = q.trim().length > 0 || hasFilter || listWhenEmpty;

  useEffect(() => {
    if (withFilters) api<RoleDef[]>('/admin/rbac/roles').then(setRoles).catch(() => setRoles([]));
  }, [withFilters]);

  // Keep filters in sync when the parent changes them (e.g. a "KYC pending" shortcut).
  const filterKey = JSON.stringify(initialFilters);
  useEffect(() => {
    setFilters(JSON.parse(filterKey) as UserFilters);
    setPage(0);
  }, [filterKey]);

  const run = async (query: string, f: UserFilters, p: number) => {
    const n = ++seq.current;
    const params = new URLSearchParams({ q: query.trim(), page: String(p), size: '20' });
    for (const [k, v] of Object.entries(f)) if (v) params.set(k, v);
    setLoading(true);
    try {
      const r = await api<PageResponse<UserHit>>(`/staff/users?${params}`);
      if (n === seq.current) {
        setResult(r);
        setError(null);
      }
    } catch (err) {
      if (n === seq.current) setError(errorMessage(err));
    } finally {
      if (n === seq.current) setLoading(false);
    }
  };

  // Search as you type (debounced); filters and paging apply at once.
  useEffect(() => {
    if (!active) {
      setResult(null);
      return;
    }
    const t = window.setTimeout(() => run(q, filters, page), 300);
    return () => window.clearTimeout(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [q, filters, page, active]);

  const onSubmit = (e: FormEvent) => {
    e.preventDefault();
    run(q, filters, page);
  };

  const setFilter = (k: keyof UserFilters, v: string) => {
    setFilters((prev) => ({ ...prev, [k]: v || undefined }));
    setPage(0);
  };

  const rows = (result?.content ?? []).filter((u) => !excludeIds.includes(u.id));

  return (
    <div className="stack user-search">
      <form className="row" onSubmit={onSubmit} role="search">
        <input
          type="search"
          aria-label="Tìm người dùng"
          placeholder={placeholder}
          value={q}
          onChange={(e) => {
            setQ(e.target.value);
            setPage(0);
          }}
          style={{ flex: '1 1 260px', maxWidth: 480 }}
        />
        <button className="btn btn-primary">Tìm</button>
      </form>
      {withFilters && (
        <div className="row">
          <select aria-label="Lọc trạng thái" value={filters.status ?? ''} onChange={(e) => setFilter('status', e.target.value)} style={{ width: 'auto' }}>
            <option value="">Mọi trạng thái</option>
            {['ACTIVE', 'RESTRICTED', 'SUSPENDED'].map((s) => (
              <option key={s} value={s}>
                {labelOf(USER_STATUS, s)}
              </option>
            ))}
          </select>
          <select aria-label="Lọc mức rủi ro" value={filters.riskTier ?? ''} onChange={(e) => setFilter('riskTier', e.target.value)} style={{ width: 'auto' }}>
            <option value="">Mọi mức rủi ro</option>
            {['STANDARD', 'HIGH'].map((s) => (
              <option key={s} value={s}>
                {labelOf(RISK_TIER, s)}
              </option>
            ))}
          </select>
          <select aria-label="Lọc vai trò" value={filters.role ?? ''} onChange={(e) => setFilter('role', e.target.value)} style={{ width: 'auto' }}>
            <option value="">Mọi vai trò</option>
            {roles.map((r) => (
              <option key={r.code} value={r.code}>
                {r.name}
              </option>
            ))}
          </select>
        </div>
      )}
      {error && <p className="error">{error}</p>}
      {active && result && (
        <>
          <p className="muted" style={{ margin: 0 }}>
            {loading ? 'Đang tìm…' : `${result.totalElements} người dùng`}
          </p>
          {rows.length > 0 && (
            <div className="table-scroll">
              <table>
                <thead>
                  <tr>
                    <th>Người dùng</th>
                    <th>Vai trò</th>
                    <th>Trạng thái</th>
                    {q.trim() && <th>Khớp theo</th>}
                    <th />
                  </tr>
                </thead>
                <tbody>
                  {rows.map((u) => (
                    <tr key={u.id}>
                      <td>
                        <strong>{u.displayName}</strong>
                        <br />
                        <span className="muted">{u.email}</span>
                        <br />
                        <span className="muted secret" title={u.id}>
                          {u.id.slice(0, 8)}
                        </span>
                      </td>
                      <td>{u.roles.map((r) => labelOf(ROLE, r)).join(', ')}</td>
                      <td>
                        <StatusBadge status={u.status} dict={USER_STATUS} />
                        {u.riskTier === 'HIGH' && (
                          <>
                            {' '}
                            <StatusBadge status={u.riskTier} dict={RISK_TIER} />
                          </>
                        )}
                      </td>
                      {q.trim() && <td className="muted">{u.matchedBy.map((m) => MATCH_LABEL[m] ?? m).join(', ')}</td>}
                      <td>
                        <button type="button" className="btn" onClick={() => onPick(u)}>
                          {pickLabel}
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          {result.totalPages > 1 && (
            <div className="row">
              <button type="button" className="btn" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
                ‹ Trước
              </button>
              <span className="muted">
                Trang {page + 1}/{result.totalPages}
              </span>
              <button type="button" className="btn" disabled={page + 1 >= result.totalPages} onClick={() => setPage((p) => p + 1)}>
                Sau ›
              </button>
            </div>
          )}
          {!loading && result.totalElements === 0 && (
            <p className="muted" style={{ margin: 0 }}>
              Không tìm thấy. Thử email đầy đủ, vài chữ trong tên (không cần dấu), hoặc 8 ký tự đầu của mã người dùng / mã đơn / mã tin đăng.
            </p>
          )}
        </>
      )}
    </div>
  );
}
