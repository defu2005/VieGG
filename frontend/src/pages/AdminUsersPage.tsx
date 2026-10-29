import { useCallback, useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { api } from '../api/client';
import type { AbuseSignal, Me, RiskTier, RoleDef, UserStatus } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { CopyId } from '../components/CopyId';
import { PageHelp } from '../components/PageHelp';
import { UserSearch, type UserFilters } from '../components/UserSearch';
import { invalidateUserBrief } from '../lib/userDirectory';
import { ReasonPicker } from '../components/ReasonPicker';
import { StatusBadge } from '../components/StatusBadge';
import { errorMessage, formatDateTime } from '../lib/format';
import { humanize, KYC_STATUS, labelOf, RISK_TIER, ROLE, USER_STATUS } from '../lib/labels';
import { FlashMessages, useFlash } from '../lib/useFlash';

type Preset = { key: string; label: string; filters: UserFilters; perm?: string };

const PRESETS: Preset[] = [
  { key: 'all', label: 'Tất cả', filters: {} },
  { key: 'risk', label: 'Rủi ro cao', filters: { riskTier: 'HIGH' } },
  { key: 'suspended', label: 'Đang bị khoá', filters: { status: 'SUSPENDED' } },
  { key: 'restricted', label: 'Đang bị hạn chế', filters: { status: 'RESTRICTED' } },
];

/**
 * Staff tool: find a user by whatever is at hand (email, name, user id, order / listing / withdrawal id),
 * or browse with filters (status, risk tier, role), then act on them.
 */
export function AdminUsersPage() {
  const { me, can } = useAuth();
  const [params, setParams] = useSearchParams();
  const selectedId = params.get('id');
  const presetKey = params.get('view') ?? 'all';
  const preset = PRESETS.find((p) => p.key === presetKey) ?? PRESETS[0];
  const [user, setUser] = useState<Me | null>(null);
  const [signals, setSignals] = useState<AbuseSignal[]>([]);
  const flash = useFlash();

  const [status, setStatus] = useState<UserStatus>('ACTIVE');
  const [statusReason, setStatusReason] = useState('');
  const [risk, setRisk] = useState<RiskTier>('STANDARD');
  const [riskReason, setRiskReason] = useState('');
  const [kycNote, setKycNote] = useState('');

  const load = useCallback(
    async (id: string) => {
      flash.setError(null);
      try {
        const u = await api<Me>(`/admin/users/${id}`);
        setUser(u);
        setStatus(u.status as UserStatus);
        setRisk(u.riskTier as RiskTier);
        invalidateUserBrief(id);
        setSignals(can('user:read_abuse_signals') ? await api<AbuseSignal[]>(`/admin/users/${id}/abuse-signals`) : []);
      } catch (err) {
        setUser(null);
        setSignals([]);
        flash.setError(errorMessage(err));
      }
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [],
  );

  useEffect(() => {
    if (selectedId) load(selectedId.trim());
    else setUser(null);
  }, [selectedId, load]);

  const pick = (id: string) => setParams({ view: presetKey, id });
  const backToList = () => setParams(presetKey === 'all' ? {} : { view: presetKey });

  const act = async (path: string, body: unknown, msg: string) => {
    if (!user) return;
    const ok = await flash.run(() => api(`/admin/users/${user.id}/${path}`, { method: 'POST', body }), msg);
    if (ok) load(user.id);
  };

  return (
    <div className="stack">
      <h1>Người dùng</h1>
      <PageHelp id="admin-users" title="Tìm và xử lý người dùng">
        <p>
          Gõ bất cứ thông tin nào bạn có: <strong>email</strong>, <strong>tên</strong> (không cần dấu), <strong>mã người dùng</strong>, hoặc{' '}
          <strong>mã đơn / mã tin đăng / mã lệnh rút</strong> mà người đó liên quan — đủ cả mã hoặc chỉ 8 ký tự đầu như trên màn hình. Cột “Khớp
          theo” cho biết vì sao người đó hiện ra.
        </p>
        <p>
          Người dùng tự xem mã của mình ở trang <strong>Hồ sơ</strong> và có thể gửi cho hỗ trợ. Ở trang chi tiết đơn, khiếu nại, kiểm duyệt và tài
          chính, bấm vào tên người dùng để mở thẳng trang này.
        </p>
        <p>Mọi thay đổi trạng thái hoặc mức rủi ro cần chọn lý do và đều được ghi lại.</p>
      </PageHelp>
      <FlashMessages error={flash.error} notice={flash.notice} />

      {!selectedId && (
        <section className="card stack">
          <div className="chips" role="tablist" aria-label="Danh sách nhanh">
            {PRESETS.filter((p) => !p.perm || can(p.perm)).map((p) => (
              <button
                key={p.key}
                role="tab"
                aria-selected={p.key === preset.key}
                className={`chip ${p.key === preset.key ? 'active' : ''}`}
                onClick={() => setParams(p.key === 'all' ? {} : { view: p.key })}
              >
                {p.label}
              </button>
            ))}
          </div>
          <UserSearch withFilters listWhenEmpty initialFilters={preset.filters} pickLabel="Mở" onPick={(u) => pick(u.id)} />
        </section>
      )}

      {selectedId && (
        <div className="row">
          <button className="btn btn-ghost" onClick={backToList}>
            ‹ Tìm người khác
          </button>
        </div>
      )}

      {selectedId && user && (
        <>
          <section className="card">
            <h2>{user.displayName}</h2>
            <dl className="kv">
              <dt>Mã người dùng</dt>
              <dd>
                <span className="secret">{user.id}</span> <CopyId id={user.id} label="copy" />
              </dd>
              <dt>Email</dt>
              <dd>{user.email}</dd>
              <dt>Vai trò</dt>
              <dd>{user.roles.map((r) => labelOf(ROLE, r)).join(', ')}</dd>
              <dt>Trạng thái</dt>
              <dd><StatusBadge status={user.status} dict={USER_STATUS} /></dd>
              <dt>Mức rủi ro</dt>
              <dd><StatusBadge status={user.riskTier} dict={RISK_TIER} /></dd>
              {/* KYC is not used yet: shown only once a user actually has a KYC state. */}
              {user.kycStatus !== 'NONE' && (
                <>
                  <dt>Xác minh danh tính</dt>
                  <dd><StatusBadge status={user.kycStatus} dict={KYC_STATUS} /></dd>
                </>
              )}
            </dl>
            {(can('dispute:read_any') || can('withdrawal:list_all') || can('ledger:read')) && (
              <div className="row">
                <span className="muted">Liên quan:</span>
                {can('dispute:read_any') && <Link to={`/support/disputes?q=${encodeURIComponent(user.email)}`}>Khiếu nại</Link>}
                {can('withdrawal:list_all') && <Link to={`/finance?ownerId=${user.id}`}>Lệnh rút tiền</Link>}
                {can('ledger:read') && <Link to={`/finance?q=${encodeURIComponent(user.email)}#ledger`}>Đơn &amp; bút toán</Link>}
              </div>
            )}
          </section>

          {can('role:read') && <UserRoles userId={user.id} roles={user.roles} canAssign={can('role:assign') && user.id !== me?.id} onChanged={() => load(user.id)} />}

          <section className="card stack">
            <h2>Đổi trạng thái tài khoản</h2>
            <p className="muted" style={{ margin: 0 }}>
              Hạn chế: tạm khóa một số chức năng. Khóa: không thể sử dụng hệ thống.
            </p>
            <div className="row" style={{ alignItems: 'flex-end' }}>
              <select aria-label="Trạng thái mới" value={status} onChange={(e) => setStatus(e.target.value as UserStatus)} style={{ width: 'auto' }}>
                <option value="ACTIVE">{labelOf(USER_STATUS, 'ACTIVE')}</option>
                <option value="RESTRICTED">{labelOf(USER_STATUS, 'RESTRICTED')}</option>
                <option value="SUSPENDED">{labelOf(USER_STATUS, 'SUSPENDED')}</option>
              </select>
              <ReasonPicker id="status-reason" value={statusReason} onChange={setStatusReason} options={['SUSPECTED_FRAUD', 'ABUSIVE_BEHAVIOR', 'CHARGEBACK', 'TOS_VIOLATION', 'RISK_CLEARED', 'CORRECTION']} />
              <button className="btn btn-danger" disabled={flash.busy || !statusReason.trim()} onClick={() => act('status', { status, reasonCode: statusReason }, 'Đã đổi trạng thái.')}>
                Áp dụng
              </button>
            </div>
          </section>

          <section className="card stack">
            <h2>Mức rủi ro</h2>
            <p className="muted" style={{ margin: 0 }}>
              Người dùng rủi ro cao bị giữ doanh thu lâu hơn trước khi được rút.
            </p>
            <div className="row" style={{ alignItems: 'flex-end' }}>
              <select aria-label="Mức rủi ro mới" value={risk} onChange={(e) => setRisk(e.target.value as RiskTier)} style={{ width: 'auto' }}>
                <option value="STANDARD">{labelOf(RISK_TIER, 'STANDARD')}</option>
                <option value="HIGH">{labelOf(RISK_TIER, 'HIGH')}</option>
              </select>
              <ReasonPicker id="risk-reason" value={riskReason} onChange={setRiskReason} options={['SUSPECTED_FRAUD', 'CHARGEBACK', 'ABUSIVE_BEHAVIOR', 'RISK_CLEARED', 'CORRECTION']} />
              <button className="btn" disabled={flash.busy || !riskReason.trim()} onClick={() => act('risk-tier', { riskTier: risk, reasonCode: riskReason }, 'Đã đổi mức rủi ro.')}>
                Áp dụng
              </button>
            </div>
          </section>

          {can('user:kyc_decide') && user.kycStatus === 'PENDING' && (
            <section className="card stack">
              <h2>Duyệt xác minh danh tính</h2>
              <div className="row">
                <input aria-label="Ghi chú KYC" placeholder="Ghi chú" value={kycNote} onChange={(e) => setKycNote(e.target.value)} style={{ maxWidth: 320 }} />
                <button className="btn btn-primary" disabled={flash.busy} onClick={() => act('kyc', { decision: 'VERIFY', note: kycNote || undefined }, 'Đã xác minh KYC.')}>
                  Xác minh
                </button>
                <button className="btn btn-danger" disabled={flash.busy} onClick={() => act('kyc', { decision: 'REJECT', note: kycNote || undefined }, 'Đã từ chối KYC.')}>
                  Từ chối
                </button>
              </div>
            </section>
          )}

          <section className="card stack">
            <h2>Tín hiệu lạm dụng</h2>
            {signals.length === 0 ? (
              <p className="muted">Không có tín hiệu nào.</p>
            ) : (
              <table>
                <thead>
                  <tr>
                    <th>Loại</th>
                    <th>Đơn</th>
                    <th>Thời gian</th>
                  </tr>
                </thead>
                <tbody>
                  {signals.map((s, i) => (
                    <tr key={i}>
                      <td>{humanize(s.type)}</td>
                      <td>{s.orderId ? <Link to={`/orders/${s.orderId}`}>{s.orderId.slice(0, 8)}</Link> : '—'}</td>
                      <td>{formatDateTime(s.createdAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </section>
        </>
      )}
    </div>
  );
}

/** Roles of the looked-up user; grant / revoke needs role:assign (and never on yourself). */
function UserRoles({ userId, roles, canAssign, onChanged }: { userId: string; roles: string[]; canAssign: boolean; onChanged: () => void }) {
  const [all, setAll] = useState<RoleDef[]>([]);
  const [pick, setPick] = useState('');
  const flash = useFlash();

  useEffect(() => {
    if (canAssign) api<RoleDef[]>('/admin/rbac/roles').then(setAll).catch(() => setAll([]));
  }, [canAssign]);

  const grant = async () => {
    if (!pick) return;
    const ok = await flash.run(() => api(`/admin/rbac/users/${userId}/roles`, { method: 'POST', body: { role: pick } }), `Đã gán vai trò ${labelOf(ROLE, pick)}.`);
    if (ok) {
      setPick('');
      onChanged();
    }
  };

  const revoke = async (role: string) => {
    const ok = await flash.run(() => api(`/admin/rbac/users/${userId}/roles/${role}`, { method: 'DELETE' }), `Đã gỡ vai trò ${labelOf(ROLE, role)}.`);
    if (ok) onChanged();
  };

  const available = all.filter((r) => !roles.includes(r.code));

  return (
    <section className="card stack">
      <h2>Vai trò</h2>
      <FlashMessages error={flash.error} notice={flash.notice} />
      <div className="chips">
        {roles.map((r) => (
          <span key={r} className="chip">
            {labelOf(ROLE, r)}
            {canAssign && r !== 'USER' && (
              <button className="btn-ghost" style={{ border: 0, background: 'none', cursor: 'pointer', marginLeft: 4 }} aria-label={`Gỡ vai trò ${labelOf(ROLE, r)}`} disabled={flash.busy} onClick={() => revoke(r)}>
                ×
              </button>
            )}
          </span>
        ))}
      </div>
      {canAssign && available.length > 0 && (
        <div className="row">
          <select aria-label="Vai trò cần gán" value={pick} onChange={(e) => setPick(e.target.value)} style={{ width: 'auto' }}>
            <option value="">Chọn vai trò…</option>
            {available.map((r) => (
              <option key={r.code} value={r.code}>
                {r.name}
              </option>
            ))}
          </select>
          <button className="btn" disabled={!pick || flash.busy} onClick={grant}>
            Gán vai trò
          </button>
        </div>
      )}
    </section>
  );
}
