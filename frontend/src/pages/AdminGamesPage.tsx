import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { api, ApiError } from '../api/client';
import type { AccountKeyRule, AccountKeyType, GamePolicy, GameRiskInput, GameView, HandoverMode, SuggestedPackage, TosStatus } from '../api/types';
import { GameRisksEditor, toRiskInputs } from '../components/GameRisksEditor';
import { cleanPackages, SuggestedPackagesEditor } from '../components/SuggestedPackagesEditor';
import { useAuth } from '../auth/AuthContext';
import { errorMessage, formatVnd } from '../lib/format';
import { PageHelp } from '../components/PageHelp';
import { HANDOVER_MODE, labelOf } from '../lib/labels';
import { FlashMessages, useFlash } from '../lib/useFlash';

const TOS_STATUSES: TosStatus[] = ['ALLOWED', 'REVIEW_REQUIRED', 'PROHIBITED', 'SUSPENDED'];

const TOS_LABELS: Record<TosStatus, string> = {
  ALLOWED: 'Được phép cho thuê',
  REVIEW_REQUIRED: 'Chờ thẩm định điều khoản',
  PROHIBITED: 'Bị cấm',
  SUSPENDED: 'Tạm ngưng',
};

/** Games waiting for review first, so the admin sees what needs a decision. */
const TOS_ORDER: Record<TosStatus, number> = { REVIEW_REQUIRED: 0, ALLOWED: 1, SUSPENDED: 2, PROHIBITED: 3 };

/** Defaults of System Design §14.1, matching GamePolicy.defaults on the backend. */
const DEFAULT_POLICY: GamePolicy = {
  name: '',
  publisher: null,
  tosUrl: null,
  handoverMode: 'STATIC_CREDENTIAL',
  passwordChangeKicksSessions: false,
  depositMin: 50000,
  depositMax: 500000,
  depositHoldHours: 72,
  sellerHoldHours: 72,
  sellerHoldHoursHighRisk: 168,
  complaintWindowMinutes: 15,
  minDurationMinutes: 60,
  maxDurationMinutes: 1440,
  graceMinutes: 10,
  platformFeeBps: 1000,
  renterFreeCancelMinutes: 120,
  lateCancelRefundBps: 5000,
  ownerFreeCancelHours: 12,
  minAge: 18,
  riskNotes: null,
  riskLevel: 'MEDIUM',
  accountKey: { type: 'LOGIN', label: null, example: null, perServer: false },
};

const ACCOUNT_KEY_TYPES: { value: AccountKeyType; label: string; hint: string }[] = [
  { value: 'NUMERIC_ID', label: 'UID dạng số', hint: 'Ví dụ Genshin, Free Fire: 800123456. Bỏ qua khoảng trắng và số 0 ở đầu.' },
  { value: 'UUID', label: 'UUID', hint: 'Ví dụ UUID nhân vật Minecraft. Nhận cả dạng có hoặc không có dấu gạch.' },
  { value: 'NAME', label: 'Tên / tag trong game', hint: 'Ví dụ Riot ID "Tên#TAG". Không phân biệt hoa thường, bỏ khoảng trắng thừa và ký tự ẩn.' },
  { value: 'LOGIN', label: 'Tên đăng nhập', hint: 'Chỉ dùng khi game không có ID công khai. Owner có thể lách bằng cách đổi tên đăng nhập.' },
];

const NUMBER_FIELDS: { key: keyof GamePolicy; label: string; hint?: string }[] = [
  { key: 'depositMin', label: 'Cọc tối thiểu (đ)' },
  { key: 'depositMax', label: 'Cọc tối đa (đ)' },
  { key: 'depositHoldHours', label: 'Giữ cọc sau thuê (giờ)', hint: '1–720' },
  { key: 'sellerHoldHours', label: 'Giữ doanh thu (giờ)', hint: '0–720' },
  { key: 'sellerHoldHoursHighRisk', label: 'Giữ doanh thu, seller rủi ro cao (giờ)', hint: '≥ giữ doanh thu' },
  { key: 'complaintWindowMinutes', label: 'Cửa sổ khiếu nại (phút)', hint: '5–120' },
  { key: 'minDurationMinutes', label: 'Thời lượng tối thiểu (phút)' },
  { key: 'maxDurationMinutes', label: 'Thời lượng tối đa (phút)' },
  { key: 'graceMinutes', label: 'Thời gian ân hạn (phút)', hint: '0–30' },
  { key: 'platformFeeBps', label: 'Phí nền tảng (bps)', hint: '1000 = 10%' },
  { key: 'renterFreeCancelMinutes', label: 'Hủy miễn phí (phút)' },
  { key: 'lateCancelRefundBps', label: 'Hoàn khi hủy muộn (bps)', hint: '5000 = 50%' },
  { key: 'ownerFreeCancelHours', label: 'Chủ hủy miễn phí (giờ)' },
  { key: 'minAge', label: 'Tuổi tối thiểu', hint: '≥ 18' },
];

const FIELD_LABELS: Record<string, string> = {
  code: 'Mã game',
  name: 'Tên game',
  publisher: 'Nhà phát hành',
  tosUrl: 'Link ToS',
  handoverMode: 'Cách bàn giao',
  passwordChangeKicksSessions: 'Đăng xuất khi đổi mật khẩu',
  riskNotes: 'Mô tả rủi ro',
  riskLevel: 'Mức rủi ro',
  ...Object.fromEntries(NUMBER_FIELDS.map((f) => [f.key, f.label])),
};

/** Cross-field checks on GamePolicy are reported under their validator name; show them on the field to fix. */
const FIELD_ALIASES: Record<string, string> = {
  depositRangeValid: 'depositMax',
  durationRangeValid: 'maxDurationMinutes',
  sellerHoldValid: 'sellerHoldHoursHighRisk',
};

/** Maps a VALIDATION_ERROR / MALFORMED_REQUEST response to form field keys (create nests the policy under "policy."). */
function fieldErrorsOf(err: unknown): Record<string, string> {
  if (!(err instanceof ApiError) || !err.details) return {};
  const raw: { field: string; message: string }[] = [];
  const { fields, field } = err.details as { fields?: unknown; field?: unknown };
  if (Array.isArray(fields)) {
    for (const f of fields as { field?: unknown; message?: unknown }[]) {
      if (typeof f.field === 'string') raw.push({ field: f.field, message: String(f.message ?? 'không hợp lệ') });
    }
  } else if (typeof field === 'string') {
    raw.push({ field, message: 'sai kiểu dữ liệu' });
  }
  const out: Record<string, string> = {};
  for (const { field: f, message } of raw) {
    const name = f.replace(/^policy\./, '');
    const key = FIELD_ALIASES[name] ?? name;
    out[key] = out[key] ? `${out[key]}; ${message}` : message;
  }
  return out;
}

/** Inline error under a field. */
function FieldError({ id, message }: { id: string; message?: string }) {
  return message ? (
    <span id={`${id}-err`} className="field-error">
      {message}
    </span>
  ) : null;
}

function toPolicy(g: GameView): GamePolicy {
  const { id: _id, code: _code, tosStatus: _tos, tosReviewedAt: _at, risks: _risks, accountKeyLabel: _kl, suggestedPackages: _sp, ...rest } = g;
  return rest;
}

/** Risk list body: trimmed names, blank descriptions dropped. */
function cleanRisks(risks: GameRiskInput[]): GameRiskInput[] {
  return risks.map((r) => ({
    ...r,
    name: r.name.trim(),
    description: r.description?.trim() || null,
    recommendation: r.recommendation?.trim() || null,
  }));
}

export function AdminGamesPage() {
  const { can } = useAuth();
  const canCreate = can('game:create');
  const canUpdate = can('game:update');
  const canChangeTos = can('game:change_tos');
  const [games, setGames] = useState<GameView[]>([]);
  const flash = useFlash();
  const [editing, setEditing] = useState<string | 'new' | null>(null);

  const load = useCallback(() => {
    api<GameView[]>('/games?all=true')
      .then(setGames)
      .catch((err) => flash.setError(errorMessage(err)));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  /** ToS changes for games already reviewed; first-time review of a new game happens on the Approvals page. */
  const changeTos = async (g: GameView, tosStatus: TosStatus) => {
    const note = window.prompt(`Ghi chú khi chuyển ${g.name} sang "${TOS_LABELS[tosStatus]}" (có thể để trống):`);
    if (note === null) return; // cancelled
    const res = await flash.run(async () => {
      const out = await api<unknown>(`/admin/games/${g.id}/tos-status`, {
        method: 'POST',
        body: { tosStatus, note: note.trim() || undefined },
      });
      // Re-allowing a prohibited or suspended game returns an approval request (202), not the game.
      if (out && typeof out === 'object' && 'actionType' in out) {
        flash.setNotice('Đã tạo yêu cầu phê duyệt kép GAME_ALLOW. Cần người thứ hai duyệt ở mục Phê duyệt.');
      } else if (tosStatus === 'ALLOWED') {
        flash.setNotice(`Đã duyệt ${g.name}. Game đã hiển thị cho người dùng và có thể đăng listing.`);
      } else {
        flash.setNotice(`Đã chuyển ${g.name} sang "${TOS_LABELS[tosStatus]}".`);
      }
    });
    if (res) load();
  };

  const sortedGames = [...games].sort((a, b) => TOS_ORDER[a.tosStatus] - TOS_ORDER[b.tosStatus]);
  const pendingCount = games.filter((g) => g.tosStatus === 'REVIEW_REQUIRED').length;

  return (
    <div className="stack">
      <div className="row spread">
        <h1>Quản lý game</h1>
        {canCreate && (
          <button className="btn btn-primary" onClick={() => setEditing(editing === 'new' ? null : 'new')}>
            {editing === 'new' ? 'Đóng' : 'Thêm game'}
          </button>
        )}
      </div>
      <PageHelp id="admin-games" title="Quản lý game và chính sách">
        <p>Mỗi game có bộ quy định riêng: mức cọc tối thiểu / tối đa, phí nền tảng, thời lượng thuê, thời gian khiếu nại và chính sách hủy. Các quy định này áp dụng cho mọi bài đăng của game đó.</p>
        <p>Game mới tạo sẽ ở trạng thái “Chờ thẩm định điều khoản” (kiểm tra điều khoản của nhà phát hành có cho phép cho thuê không) và chỉ hiển thị sau khi được duyệt.</p>
      </PageHelp>
      <FlashMessages error={flash.error} notice={flash.notice} />
      {pendingCount > 0 && (
        <p className="banner">
          Có {pendingCount} game đang chờ thẩm định điều khoản. Game chỉ hiển thị cho người dùng và nhận listing sau khi được duyệt ở{' '}
          <Link to="/admin/approvals">mục Phê duyệt</Link>.
        </p>
      )}

      {editing === 'new' && (
        <GameForm
          onSaved={() => {
            setEditing(null);
            flash.setError(null);
            flash.setNotice('Đã tạo game ở trạng thái chờ thẩm định điều khoản. Vào mục Phê duyệt để duyệt game trước khi cho thuê.');
            load();
          }}
        />
      )}

      {sortedGames.map((g) => (
        <div key={g.id} className="card stack">
          <div className="row spread">
            <div>
              <h2 style={{ marginBottom: '0.2rem' }}>{g.name}</h2>
              <p className="muted" style={{ margin: 0 }}>
                Mã: {g.code} · {labelOf(HANDOVER_MODE, g.handoverMode)} · cọc {formatVnd(g.depositMin)}–{formatVnd(g.depositMax)} · phí {g.platformFeeBps / 100}%
              </p>
              <p className="muted" style={{ margin: 0 }}>
                Định danh account: {g.accountKeyLabel}
                {g.accountKey.perServer ? ' (riêng theo server)' : ''}
                {g.accountKey.type === 'LOGIN' && ' · chưa có ID trong game, dễ bị đăng trùng'}
              </p>
              <p className="muted" style={{ margin: 0 }}>
                {g.risks.length === 0
                  ? 'Chưa khai báo rủi ro'
                  : `Rủi ro: ${g.risks.filter((r) => r.side === 'OWNER').length} của chủ account · ${g.risks.filter((r) => r.side === 'RENTER').length} của người thuê`}
              </p>
            </div>
            <div className="row">
              <span className={`badge ${g.tosStatus === 'ALLOWED' ? 'badge-ok' : g.tosStatus === 'REVIEW_REQUIRED' ? 'badge-warn' : 'badge-bad'}`}>
                {TOS_LABELS[g.tosStatus]}
              </span>
            </div>
          </div>
          {g.tosStatus === 'REVIEW_REQUIRED' && (
            <p className="banner" style={{ margin: 0 }}>
              Game này chưa hiển thị cho người dùng. Duyệt hoặc từ chối tại <Link to="/admin/approvals">mục Phê duyệt</Link>.
            </p>
          )}
          {(canChangeTos || canUpdate) && (
            <div className="row">
              {canChangeTos && g.tosStatus !== 'REVIEW_REQUIRED' && (
              <select
                aria-label={`Đổi ToS của ${g.name}`}
                value=""
                onChange={(e) => e.target.value && changeTos(g, e.target.value as TosStatus)}
                style={{ width: 'auto' }}
              >
                <option value="">Đổi trạng thái ToS…</option>
                {TOS_STATUSES.filter((s) => s !== g.tosStatus).map((s) => (
                  <option key={s} value={s}>
                    {TOS_LABELS[s]}
                  </option>
                ))}
              </select>
              )}
              {canUpdate && (
                <button className="btn" onClick={() => setEditing(editing === g.id ? null : g.id)}>
                  {editing === g.id ? 'Đóng' : 'Sửa chính sách & rủi ro'}
                </button>
              )}
            </div>
          )}
          {editing === g.id && (
            <GameForm
              game={g}
              onSaved={() => {
                setEditing(null);
                flash.setError(null);
                flash.setNotice('Đã lưu chính sách. Thay đổi chỉ áp dụng cho đơn mới.');
                load();
              }}
            />
          )}
        </div>
      ))}
    </div>
  );
}

function GameForm({ game, onSaved }: { game?: GameView; onSaved: () => void }) {
  const [code, setCode] = useState('');
  const [policy, setPolicy] = useState<GamePolicy>(game ? toPolicy(game) : DEFAULT_POLICY);
  const [risks, setRisks] = useState<GameRiskInput[]>(toRiskInputs(game?.risks));
  const [packages, setPackages] = useState<SuggestedPackage[]>(game?.suggestedPackages ?? []);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const sfx = game?.id ?? 'new';

  /** Editing a field clears its error; the others stay until the next submit. */
  const clearError = (key: string) =>
    setFieldErrors((prev) => {
      if (!(key in prev)) return prev;
      const { [key]: _fixed, ...rest } = prev;
      return rest;
    });

  const set = <K extends keyof GamePolicy>(key: K, value: GamePolicy[K]) => {
    setPolicy((p) => ({ ...p, [key]: value }));
    clearError(key);
  };

  const keyRule: AccountKeyRule = policy.accountKey ?? DEFAULT_POLICY.accountKey!;
  const setKey = (patch: Partial<AccountKeyRule>) => set('accountKey', { ...keyRule, ...patch });
  const keyHint = ACCOUNT_KEY_TYPES.find((t) => t.value === keyRule.type)?.hint;

  /** Props marking an input invalid and linking it to its inline error. */
  const invalidProps = (key: string, id: string) =>
    fieldErrors[key] ? { 'aria-invalid': true, 'aria-describedby': `${id}-err` } : {};

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setSubmitting(true);
    setError(null);
    setFieldErrors({});
    const body: GamePolicy = { ...policy, publisher: policy.publisher || null, tosUrl: policy.tosUrl || null, riskNotes: policy.riskNotes || null };
    try {
      if (game) {
        await api(`/admin/games/${game.id}`, { method: 'PUT', body });
        await api(`/admin/games/${game.id}/risks`, { method: 'PUT', body: { risks: cleanRisks(risks) } });
        await api(`/admin/games/${game.id}/packages`, { method: 'PUT', body: { packages: cleanPackages(packages) } });
      } else {
        const created = await api<GameView>('/admin/games', { method: 'POST', body: { code, policy: body, risks: cleanRisks(risks) } });
        if (packages.length > 0) {
          await api(`/admin/games/${created.id}/packages`, { method: 'PUT', body: { packages: cleanPackages(packages) } });
        }
      }
      onSaved();
    } catch (err) {
      const byField = fieldErrorsOf(err);
      setFieldErrors(byField);
      const keys = Object.keys(byField);
      setError(
        keys.length > 0
          ? `Chưa lưu được, vui lòng sửa ${keys.length} trường: ${keys.map((k) => `${FIELD_LABELS[k] ?? k} — ${byField[k].replace(/\.$/, '')}`).join('; ')}.`
          : errorMessage(err),
      );
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <form className="form wide" onSubmit={onSubmit}>
      <div className="grid">
        {!game && (
          <div className="field">
            <label htmlFor="g-code">Mã game</label>
            <input id="g-code" {...invalidProps('code', 'g-code')} required pattern="[a-z0-9_-]{2,40}" title="2–40 ký tự: a-z, 0-9, _ hoặc -" value={code} onChange={(e) => { setCode(e.target.value); clearError('code'); }} />
            <FieldError id="g-code" message={fieldErrors.code} />
          </div>
        )}
        <div className="field">
          <label htmlFor={`g-name-${sfx}`}>Tên game</label>
          <input id={`g-name-${sfx}`} {...invalidProps('name', `g-name-${sfx}`)} required maxLength={120} value={policy.name} onChange={(e) => set('name', e.target.value)} />
          <FieldError id={`g-name-${sfx}`} message={fieldErrors.name} />
        </div>
        <div className="field">
          <label htmlFor={`g-pub-${sfx}`}>Nhà phát hành</label>
          <input id={`g-pub-${sfx}`} {...invalidProps('publisher', `g-pub-${sfx}`)} maxLength={120} value={policy.publisher ?? ''} onChange={(e) => set('publisher', e.target.value)} />
          <FieldError id={`g-pub-${sfx}`} message={fieldErrors.publisher} />
        </div>
        <div className="field">
          <label htmlFor={`g-tos-${sfx}`}>Link ToS</label>
          <input id={`g-tos-${sfx}`} {...invalidProps('tosUrl', `g-tos-${sfx}`)} type="url" value={policy.tosUrl ?? ''} onChange={(e) => set('tosUrl', e.target.value)} />
          <FieldError id={`g-tos-${sfx}`} message={fieldErrors.tosUrl} />
        </div>
        <div className="field">
          <label htmlFor={`g-hand-${sfx}`}>Cách bàn giao</label>
          <select id={`g-hand-${sfx}`} {...invalidProps('handoverMode', `g-hand-${sfx}`)} value={policy.handoverMode} onChange={(e) => set('handoverMode', e.target.value as HandoverMode)}>
            <option value="STATIC_CREDENTIAL">{labelOf(HANDOVER_MODE, 'STATIC_CREDENTIAL')}</option>
            <option value="OWNER_ASSISTED_OTP">{labelOf(HANDOVER_MODE, 'OWNER_ASSISTED_OTP')}</option>
          </select>
          <FieldError id={`g-hand-${sfx}`} message={fieldErrors.handoverMode} />
        </div>
        {NUMBER_FIELDS.map((f) => (
          <div className="field" key={f.key}>
            <label htmlFor={`g-${f.key}-${sfx}`}>{f.label}</label>
            <input
              id={`g-${f.key}-${sfx}`}
              {...invalidProps(f.key, `g-${f.key}-${sfx}`)}
              type="number"
              required
              value={policy[f.key] as number}
              onChange={(e) => set(f.key, Number(e.target.value) as never)}
            />
            {f.hint && <span className="field-hint">{f.hint}</span>}
            <FieldError id={`g-${f.key}-${sfx}`} message={fieldErrors[f.key]} />
          </div>
        ))}
      </div>
      <label className="checkbox">
        <input type="checkbox" checked={policy.passwordChangeKicksSessions} onChange={(e) => set('passwordChangeKicksSessions', e.target.checked)} />
        Đổi mật khẩu sẽ đăng xuất các phiên đang chơi
      </label>
      <fieldset className="stack" style={{ border: '1px solid var(--border, #ddd)', borderRadius: 8, padding: '0.75rem' }}>
        <legend>Định danh account (chống đăng trùng)</legend>
        <p className="muted" style={{ margin: 0 }}>
          Mỗi định danh chỉ được đăng ký một lần trên toàn VieGG, kể cả khi owner cũ bị khoá. Owner phải nhập định danh này khi thêm account.
        </p>
        <div className="grid">
          <div className="field">
            <label htmlFor={`g-keytype-${sfx}`}>Định danh bằng</label>
            <select id={`g-keytype-${sfx}`} value={keyRule.type} onChange={(e) => setKey({ type: e.target.value as AccountKeyType })}>
              {ACCOUNT_KEY_TYPES.map((t) => (
                <option key={t.value} value={t.value}>
                  {t.label}
                </option>
              ))}
            </select>
            {keyHint && <span className="field-hint">{keyHint}</span>}
          </div>
          {keyRule.type !== 'LOGIN' && (
            <>
              <div className="field">
                <label htmlFor={`g-keylabel-${sfx}`}>Tên hiển thị cho owner</label>
                <input id={`g-keylabel-${sfx}`} maxLength={60} placeholder="UID, Riot ID…" value={keyRule.label ?? ''} onChange={(e) => setKey({ label: e.target.value || null })} />
              </div>
              <div className="field">
                <label htmlFor={`g-keyex-${sfx}`}>Ví dụ</label>
                <input id={`g-keyex-${sfx}`} maxLength={120} placeholder="800123456" value={keyRule.example ?? ''} onChange={(e) => setKey({ example: e.target.value || null })} />
              </div>
            </>
          )}
        </div>
        {keyRule.type !== 'LOGIN' && (
          <label className="checkbox">
            <input type="checkbox" checked={keyRule.perServer} onChange={(e) => setKey({ perServer: e.target.checked })} />
            Định danh chỉ duy nhất trong từng server (owner phải nhập server)
          </label>
        )}
        {game && (
          <span className="field-hint">Khi game đã có account mang định danh, chỉ sửa được tên hiển thị và ví dụ.</span>
        )}
      </fieldset>
      <GameRisksEditor
        risks={risks}
        onChange={setRisks}
        idPrefix={`g-${sfx}`}
        depositMin={policy.depositMin}
        depositMax={policy.depositMax}
      />
      <SuggestedPackagesEditor
        packages={packages}
        onChange={setPackages}
        idPrefix={`g-${sfx}`}
        minDurationMinutes={policy.minDurationMinutes}
        maxDurationMinutes={policy.maxDurationMinutes}
      />
      {error && (
        <p className="error" role="alert">
          {error}
        </p>
      )}
      <div>
        <button className="btn btn-primary" disabled={submitting}>
          {submitting ? 'Đang lưu…' : game ? 'Lưu chính sách, rủi ro & gói gợi ý' : 'Tạo game'}
        </button>
      </div>
    </form>
  );
}
