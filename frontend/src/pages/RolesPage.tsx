import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react';
import { api } from '../api/client';
import type { PermissionDef, RoleDef, UserHit } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { errorMessage } from '../lib/format';
import { FlashMessages, useFlash } from '../lib/useFlash';
import { UserRef } from '../components/UserRef';
import { UserSearch } from '../components/UserSearch';
import { Pager, usePaged } from '../components/Pager';

/** RBAC admin: roles and the permissions each role grants. */
export function RolesPage() {
  const { me, can, refreshMe } = useAuth();
  const canManage = can('role:manage');
  const [roles, setRoles] = useState<RoleDef[] | null>(null);
  const [perms, setPerms] = useState<PermissionDef[]>([]);
  const [selected, setSelected] = useState<string | null>(null);
  const [draft, setDraft] = useState<Set<string>>(new Set());
  const [creating, setCreating] = useState(false);
  const flash = useFlash();

  const load = useCallback(async () => {
    try {
      const [r, p] = await Promise.all([api<RoleDef[]>('/admin/rbac/roles'), api<PermissionDef[]>('/admin/rbac/permissions')]);
      setRoles(r);
      setPerms(p);
    } catch (err) {
      flash.setError(errorMessage(err));
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const role = roles?.find((r) => r.code === selected) ?? null;

  useEffect(() => {
    setDraft(new Set(role?.permissions ?? []));
  }, [role]);

  const byResource = useMemo(() => {
    const groups = new Map<string, PermissionDef[]>();
    for (const p of perms) {
      const list = groups.get(p.resource) ?? [];
      list.push(p);
      groups.set(p.resource, list);
    }
    return [...groups.entries()];
  }, [perms]);

  const mine = new Set(me?.permissions ?? []);
  const dirty = role !== null && (draft.size !== role.permissions.length || role.permissions.some((p) => !draft.has(p)));

  const toggle = (code: string) => {
    setDraft((prev) => {
      const next = new Set(prev);
      if (next.has(code)) next.delete(code);
      else next.add(code);
      return next;
    });
  };

  const save = async () => {
    if (!role) return;
    const ok = await flash.run(
      () => api(`/admin/rbac/roles/${role.code}/permissions`, { method: 'PUT', body: { permissions: [...draft] } }),
      `Đã cập nhật quyền của ${role.code}.`,
    );
    if (ok) {
      await load();
      await refreshMe();
    }
  };

  const setStaff = async (staff: boolean) => {
    if (!role) return;
    const msg = staff
      ? `Đánh dấu ${role.code} là vai trò nhân viên? Người giữ vai trò này sẽ mất các tính năng khách hàng (thuê, cho thuê, ví).`
      : `Bỏ đánh dấu nhân viên cho ${role.code}?`;
    if (!window.confirm(msg)) return;
    const ok = await flash.run(
      () => api(`/admin/rbac/roles/${role.code}`, { method: 'PUT', body: { name: role.name, description: role.description, staff } }),
      `Đã cập nhật ${role.code}.`,
    );
    if (ok) {
      await load();
      await refreshMe();
    }
  };

  const remove = async () => {
    if (!role || !window.confirm(`Xoá vai trò ${role.code}?`)) return;
    const ok = await flash.run(() => api(`/admin/rbac/roles/${role.code}`, { method: 'DELETE' }), `Đã xoá ${role.code}.`);
    if (ok) {
      setSelected(null);
      load();
    }
  };

  return (
    <div className="stack">
      <div className="row spread">
        <h1>Phân quyền</h1>
        {canManage && (
          <button className="btn btn-primary" onClick={() => setCreating((v) => !v)}>
            {creating ? 'Đóng' : 'Thêm vai trò'}
          </button>
        )}
      </div>
      <p className="muted">
        Mỗi vai trò là một tập quyền dạng <code>tài nguyên:hành động</code>. Thay đổi có hiệu lực ngay, không cần đăng nhập lại. Bạn chỉ
        cấp được những quyền mà chính bạn đang có. Người giữ một <strong>vai trò nhân viên</strong> chỉ dùng trang quản trị: họ không còn các
        quyền khách hàng của vai trò USER (thuê, cho thuê, ví).
      </p>
      <FlashMessages error={flash.error} notice={flash.notice} />

      {creating && (
        <CreateRoleForm
          busy={flash.busy}
          onSubmit={async (body) => {
            const ok = await flash.run(() => api('/admin/rbac/roles', { method: 'POST', body }), `Đã tạo vai trò ${body.code}.`);
            if (ok) {
              setCreating(false);
              await load();
              setSelected(body.code.toUpperCase());
            }
          }}
        />
      )}

      {roles === null ? (
        <p className="muted">Đang tải…</p>
      ) : (
        <div className="chips" role="tablist" aria-label="Vai trò">
          {roles.map((r) => (
            <button
              key={r.code}
              role="tab"
              aria-selected={r.code === selected}
              className={`chip ${r.code === selected ? 'active' : ''}`}
              onClick={() => setSelected(r.code)}
            >
              {r.staff ? '🛠️ ' : ''}
              {r.name} · {r.code} ({r.userCount})
            </button>
          ))}
        </div>
      )}

      {role && (
        <section className="card stack">
          <div className="row spread">
            <div>
              <h2>
                {role.name} <span className="muted">{role.code}</span>
              </h2>
              {role.description && <p className="muted">{role.description}</p>}
              <p className="muted">
                {role.system ? 'Vai trò hệ thống (không xoá được)' : 'Vai trò tuỳ chỉnh'} · {role.staff ? 'Vai trò nhân viên' : 'Vai trò khách hàng'} ·{' '}
                {role.userCount} người dùng · {role.permissions.length} quyền
              </p>
              {canManage && role.code !== 'USER' && (
                <label className="checkbox">
                  <input type="checkbox" checked={role.staff} disabled={flash.busy} onChange={(e) => setStaff(e.target.checked)} />
                  <span>Vai trò nhân viên (không có tính năng khách hàng)</span>
                </label>
              )}
            </div>
            {canManage && (
              <div className="row">
                <button className="btn btn-primary" disabled={!dirty || flash.busy} onClick={save}>
                  Lưu quyền
                </button>
                {dirty && (
                  <button className="btn btn-ghost" onClick={() => setDraft(new Set(role.permissions))}>
                    Huỷ thay đổi
                  </button>
                )}
                {!role.system && (
                  <button className="btn btn-danger" disabled={flash.busy || role.userCount > 0} onClick={remove} title={role.userCount > 0 ? 'Gỡ vai trò khỏi mọi người dùng trước' : undefined}>
                    Xoá vai trò
                  </button>
                )}
              </div>
            )}
          </div>

          {role.code !== 'USER' && (
            <RoleMembers
              key={role.code}
              role={role}
              meId={me?.id}
              canAssign={can('role:assign')}
              onChanged={async () => {
                await load();
                await refreshMe();
              }}
            />
          )}

          <div className="grid">
            {byResource.map(([resource, list]) => (
              <fieldset key={resource} className="card" style={{ margin: 0 }}>
                <legend>
                  <strong>{resource}</strong>
                </legend>
                {list.map((p) => {
                  const checked = draft.has(p.code);
                  // Anti-escalation hint: a permission you do not hold can be removed but not added.
                  const disabled = !canManage || (!checked && !mine.has(p.code));
                  return (
                    <label key={p.code} className="checkbox" title={p.code}>
                      <input type="checkbox" checked={checked} disabled={disabled} onChange={() => toggle(p.code)} />
                      <span>
                        {p.description} <span className="muted">({p.action})</span>
                      </span>
                    </label>
                  );
                })}
              </fieldset>
            ))}
          </div>
        </section>
      )}
    </div>
  );
}

function CreateRoleForm({ busy, onSubmit }: { busy: boolean; onSubmit: (b: { code: string; name: string; description?: string; staff: boolean }) => void }) {
  const [staff, setStaff] = useState(true);
  const [code, setCode] = useState('');
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const submit = (e: FormEvent) => {
    e.preventDefault();
    onSubmit({ code: code.trim().toUpperCase(), name: name.trim(), description: description.trim() || undefined, staff });
  };
  return (
    <form className="card form" onSubmit={submit}>
      <div className="field">
        <label htmlFor="role-code">Mã vai trò</label>
        <input id="role-code" required pattern="[A-Za-z][A-Za-z0-9_]{1,39}" placeholder="VD: MODERATOR" value={code} onChange={(e) => setCode(e.target.value)} />
      </div>
      <div className="field">
        <label htmlFor="role-name">Tên hiển thị</label>
        <input id="role-name" required maxLength={80} value={name} onChange={(e) => setName(e.target.value)} />
      </div>
      <div className="field">
        <label htmlFor="role-desc">Mô tả</label>
        <input id="role-desc" maxLength={500} value={description} onChange={(e) => setDescription(e.target.value)} />
      </div>
      <label className="checkbox">
        <input type="checkbox" checked={staff} onChange={(e) => setStaff(e.target.checked)} />
        <span>Vai trò nhân viên: người giữ vai trò chỉ dùng trang quản trị, không thuê, không cho thuê, không có ví</span>
      </label>
      <p className="muted">Vai trò mới chưa có quyền nào; chọn quyền sau khi tạo.</p>
      <button className="btn btn-primary" disabled={busy}>
        Tạo vai trò
      </button>
    </form>
  );
}

/** Who holds a role, with grant / revoke by searching a user (any key: email, name, id, order id…). */
function RoleMembers({ role, meId, canAssign, onChanged }: { role: RoleDef; meId?: string; canAssign: boolean; onChanged: () => void }) {
  const [adding, setAdding] = useState(false);
  const flash = useFlash();
  const paged = usePaged<UserHit>(`/staff/users?role=${encodeURIComponent(role.code)}`, {
    onError: (err) => flash.setError(errorMessage(err)),
  });
  const members = paged.items;
  const total = paged.result?.totalElements ?? 0;
  const load = paged.reload;

  const grant = async (u: UserHit) => {
    if (role.staff && !window.confirm(`Gán ${role.name} cho ${u.displayName}? Đây là vai trò nhân viên: người này sẽ mất tính năng khách hàng (thuê, cho thuê, ví).`)) return;
    const ok = await flash.run(
      () => api(`/admin/rbac/users/${u.id}/roles`, { method: 'POST', body: { role: role.code } }),
      `Đã gán ${role.name} cho ${u.displayName}.`,
    );
    if (ok) {
      load();
      onChanged();
    }
  };

  const revoke = async (u: UserHit) => {
    if (!window.confirm(`Gỡ ${role.name} khỏi ${u.displayName}?`)) return;
    const ok = await flash.run(() => api(`/admin/rbac/users/${u.id}/roles/${role.code}`, { method: 'DELETE' }), `Đã gỡ ${role.name}.`);
    if (ok) {
      load();
      onChanged();
    }
  };

  return (
    <div className="stack">
      <div className="row spread">
        <h3 style={{ margin: 0 }}>Người giữ vai trò ({total})</h3>
        {canAssign && (
          <button className="btn" onClick={() => setAdding((v) => !v)}>
            {adding ? 'Đóng' : 'Gán cho người dùng'}
          </button>
        )}
      </div>
      <FlashMessages error={flash.error} notice={flash.notice} />
      {adding && <UserSearch pickLabel="Gán" excludeIds={(members ?? []).map((m) => m.id)} onPick={grant} />}
      {members === null ? (
        <p className="muted">Đang tải…</p>
      ) : members.length === 0 ? (
        <p className="muted">Chưa ai giữ vai trò này.</p>
      ) : (
        <ul className="stack" style={{ margin: 0, paddingLeft: '1.2rem' }}>
          {members.map((u) => (
            <li key={u.id}>
              <UserRef id={u.id} />
              {canAssign && u.id !== meId && (
                <>
                  {' '}
                  <button className="btn btn-ghost" disabled={flash.busy} onClick={() => revoke(u)}>
                    Gỡ
                  </button>
                </>
              )}
            </li>
          ))}
        </ul>
      )}
      <Pager result={paged.result} page={paged.page} onPage={paged.setPage} />
    </div>
  );
}
