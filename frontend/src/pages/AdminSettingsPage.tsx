import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react';
import { api, ApiError } from '../api/client';
import type { SettingDef } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { SiteBannerView } from '../components/SiteBanner';
import { errorMessage, formatDateTime, formatVnd } from '../lib/format';
import { useSiteSettings } from '../lib/siteSettings';
import { FlashMessages, useFlash } from '../lib/useFlash';

const GROUP_ICONS: Record<string, string> = { WITHDRAWAL: '💸', SITE: '🏷️', HOME: '🏠', BANNER: '📣' };
const CHOICE_LABELS: Record<string, string> = {
  info: 'Thông tin (xanh)',
  success: 'Thành công (xanh lá)',
  warning: 'Cảnh báo (vàng)',
  danger: 'Khẩn cấp (đỏ)',
};

/** Admin: runtime platform settings (withdrawal limits, announcement banner, site and home page texts). */
export function AdminSettingsPage() {
  const { can } = useAuth();
  const canEdit = can('setting:update');
  const site = useSiteSettings();
  const [defs, setDefs] = useState<SettingDef[] | null>(null);
  const [draft, setDraft] = useState<Record<string, string>>({});
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const flash = useFlash();

  const apply = useCallback((list: SettingDef[]) => {
    setDefs(list);
    setDraft(Object.fromEntries(list.map((d) => [d.key, d.value])));
    setFieldErrors({});
  }, []);

  const load = useCallback(async () => {
    try {
      apply(await api<SettingDef[]>('/admin/settings'));
    } catch (err) {
      flash.setError(errorMessage(err));
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [apply]);

  useEffect(() => {
    load();
  }, [load]);

  const groups = useMemo(() => {
    const out = new Map<string, { label: string; items: SettingDef[] }>();
    for (const d of defs ?? []) {
      const g = out.get(d.group) ?? { label: d.groupLabel, items: [] };
      g.items.push(d);
      out.set(d.group, g);
    }
    return [...out.entries()];
  }, [defs]);

  const changed = (defs ?? []).filter((d) => (draft[d.key] ?? '') !== d.value);
  const set = (key: string, value: string) => {
    setDraft((prev) => ({ ...prev, [key]: value }));
    setFieldErrors((prev) => {
      if (!prev[key]) return prev;
      const next = { ...prev };
      delete next[key];
      return next;
    });
  };

  const save = async (e: FormEvent) => {
    e.preventDefault();
    if (changed.length === 0) return;
    const values = Object.fromEntries(changed.map((d) => [d.key, draft[d.key] ?? '']));
    setFieldErrors({});
    let result: SettingDef[] | null = null;
    const ok = await flash.run(async () => {
      try {
        result = await api<SettingDef[]>('/admin/settings', { method: 'PUT', body: { values } });
      } catch (err) {
        const fields = err instanceof ApiError ? (err.details?.fields as Record<string, string> | undefined) : undefined;
        if (fields) setFieldErrors(fields);
        throw err;
      }
    }, `Đã lưu ${changed.length} cấu hình. Thay đổi có hiệu lực ngay.`);
    if (ok && result) {
      apply(result);
      site.reload();
    }
  };

  const resetToDefault = (d: SettingDef) => set(d.key, d.defaultValue);

  return (
    <form className="stack" onSubmit={save}>
      <div className="row spread">
        <h1>Cấu hình hệ thống</h1>
        {canEdit && (
          <div className="row">
            {changed.length > 0 && (
              <button type="button" className="btn btn-ghost" onClick={() => defs && apply(defs)} disabled={flash.busy}>
                Huỷ thay đổi
              </button>
            )}
            <button className="btn btn-primary" disabled={flash.busy || changed.length === 0}>
              {flash.busy ? 'Đang lưu…' : changed.length > 0 ? `Lưu ${changed.length} thay đổi` : 'Lưu'}
            </button>
          </div>
        )}
      </div>
      <p className="muted">
        Các cấu hình áp dụng ngay cho web và ứng dụng, không cần khởi động lại máy chủ. Mỗi lần lưu được ghi vào nhật ký kiểm toán.
        {!canEdit && ' Bạn chỉ có quyền xem.'}
      </p>
      <FlashMessages error={flash.error} notice={flash.notice} />

      {defs === null ? (
        <p className="muted">Đang tải…</p>
      ) : (
        groups.map(([group, g]) => (
          <section key={group} className="card stack" id={`settings-${group.toLowerCase()}`}>
            <h2>
              {GROUP_ICONS[group] ?? '⚙️'} {g.label}
            </h2>
            {group === 'BANNER' && <BannerPreview draft={draft} />}
            <div className="settings-grid">
              {g.items.map((d) => (
                <SettingField
                  key={d.key}
                  def={d}
                  value={draft[d.key] ?? ''}
                  error={fieldErrors[d.key]}
                  disabled={!canEdit || flash.busy}
                  dirty={(draft[d.key] ?? '') !== d.value}
                  onChange={(v) => set(d.key, v)}
                  onReset={() => resetToDefault(d)}
                />
              ))}
            </div>
          </section>
        ))
      )}
    </form>
  );
}

function SettingField({
  def,
  value,
  error,
  disabled,
  dirty,
  onChange,
  onReset,
}: {
  def: SettingDef;
  value: string;
  error?: string;
  disabled: boolean;
  dirty: boolean;
  onChange: (v: string) => void;
  onReset: () => void;
}) {
  const id = `setting-${def.key.replace(/\./g, '-')}`;
  const wide = def.type === 'LONG_TEXT';
  const isDefault = value === def.defaultValue;

  if (def.type === 'BOOLEAN') {
    return (
      <div className="field settings-field">
        <label className="checkbox">
          <input id={id} type="checkbox" checked={value === 'true'} disabled={disabled} onChange={(e) => onChange(String(e.target.checked))} />
          <span>
            {def.label} {dirty && <span className="badge badge-warn">chưa lưu</span>}
          </span>
        </label>
        {def.description && <span className="field-hint">{def.description}</span>}
        {error && <span className="field-error">{error}</span>}
      </div>
    );
  }

  let input;
  switch (def.type) {
    case 'MONEY':
    case 'INTEGER':
      input = (
        <input
          id={id}
          type="number"
          inputMode="numeric"
          step={1}
          min={def.min}
          max={def.max}
          value={value}
          disabled={disabled}
          aria-invalid={error ? true : undefined}
          onChange={(e) => onChange(e.target.value)}
        />
      );
      break;
    case 'LONG_TEXT':
      input = (
        <textarea id={id} rows={3} maxLength={2000} value={value} disabled={disabled} aria-invalid={error ? true : undefined} onChange={(e) => onChange(e.target.value)} />
      );
      break;
    case 'CHOICE':
      input = (
        <select id={id} value={value} disabled={disabled} onChange={(e) => onChange(e.target.value)}>
          {(def.choices ?? []).map((c) => (
            <option key={c} value={c}>
              {CHOICE_LABELS[c] ?? c}
            </option>
          ))}
        </select>
      );
      break;
    default:
      input = (
        <input
          id={id}
          type="text"
          maxLength={def.type === 'URL' ? 500 : 200}
          placeholder={def.type === 'URL' ? '/help hoặc https://...' : undefined}
          value={value}
          disabled={disabled}
          aria-invalid={error ? true : undefined}
          onChange={(e) => onChange(e.target.value)}
        />
      );
  }

  const shownDefault =
    def.type === 'MONEY' ? (def.defaultValue === '0' ? '0' : formatVnd(Number(def.defaultValue))) : def.defaultValue || '(trống)';

  return (
    <div className={`field settings-field${wide ? ' settings-wide' : ''}`}>
      <label htmlFor={id}>
        {def.label} {dirty && <span className="badge badge-warn">chưa lưu</span>}
      </label>
      {input}
      {def.type === 'MONEY' && value !== '' && Number(value) > 0 && <span className="field-hint">= {formatVnd(Number(value))}</span>}
      {def.description && <span className="field-hint">{def.description}</span>}
      {error && <span className="field-error">{error}</span>}
      <span className="field-hint">
        {!isDefault && !disabled ? (
          <>
            Mặc định: {shownDefault} ·{' '}
            <button type="button" className="link-button" onClick={onReset}>
              Khôi phục mặc định
            </button>
          </>
        ) : def.customized && def.updatedAt ? (
          `Sửa lần cuối ${formatDateTime(def.updatedAt)}`
        ) : (
          'Đang dùng giá trị mặc định'
        )}
      </span>
    </div>
  );
}

function BannerPreview({ draft }: { draft: Record<string, string> }) {
  const message = (draft['banner.message'] ?? '').trim();
  const enabled = draft['banner.enabled'] === 'true';
  return (
    <div className="stack" style={{ gap: '0.4rem' }}>
      <span className="field-hint">Xem trước{enabled ? '' : ' (banner đang tắt — người dùng sẽ không thấy)'}:</span>
      {message ? (
        <div className="settings-banner-preview" style={{ opacity: enabled ? 1 : 0.55 }}>
          <SiteBannerView
            content={{
              message,
              variant: draft['banner.variant'] ?? 'info',
              linkUrl: draft['banner.link_url'] ?? '',
              linkLabel: draft['banner.link_label'] ?? '',
              dismissible: draft['banner.dismissible'] === 'true',
            }}
            onDismiss={draft['banner.dismissible'] === 'true' ? () => undefined : undefined}
          />
        </div>
      ) : (
        <p className="muted" style={{ margin: 0 }}>
          Nhập nội dung banner để xem trước.
        </p>
      )}
    </div>
  );
}
