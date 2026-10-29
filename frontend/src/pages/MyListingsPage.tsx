import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { api } from '../api/client';
import type { EndMode, GameAccount, GameRisk, GameView, Listing, PageResponse, PricingTier } from '../api/types';
import { BusyBlocksManager } from '../components/BusyBlocksManager';
import { ListingImageManager } from '../components/ListingImages';
import { GameRiskPanel } from '../components/GameRiskPanel';
import { duplicateOwnerRisk, OwnerRisksEditor, ownerRiskDrafts, ownerRisksBody, withOwnerRisks } from '../components/OwnerRisksEditor';
import {
  missingRiskAmounts,
  noOwnerRiskCovered,
  riskDepositTotal,
  riskTermsOf,
  RiskTermsFields,
  type RiskTermValues,
} from '../components/RiskTermsFields';
import { PageHelp } from '../components/PageHelp';
import { Pager, usePaged } from '../components/Pager';
import { StatusBadge } from '../components/StatusBadge';
import { formatAttributes, parseAttributes } from '../lib/attributes';
import { errorMessage, formatDateTime, formatVnd } from '../lib/format';
import { END_MODE, SECURITY_STATE, hintOf, labelOf } from '../lib/labels';
import { durationLabel, FREE_CANCEL_CHOICES, PACKAGE_DURATIONS } from '../lib/pricing';
import { FlashMessages, useFlash } from '../lib/useFlash';

const END_MODES: { value: EndMode; label: string }[] = [
  { value: 'FORCE_AT_END', label: 'Hết giờ là đổi mật khẩu ngay' },
  { value: 'GRACE_FOR_CURRENT_MATCH', label: 'Cho thêm thời gian để kết thúc trận' },
  { value: 'STAY_ONLINE_UNTIL_RECLAIM', label: 'Giữ đăng nhập đến khi lấy lại' },
];

/** Package durations an owner can offer (filtered by the game's min/max). */
const TIER_DURATIONS = PACKAGE_DURATIONS;

/** Starting packages of a new listing: the game's suggested packages (owner unticks what they do not offer). */
function initialTiers(game: GameView | undefined): PricingTier[] {
  const suggested = game?.suggestedPackages ?? [];
  if (suggested.length > 0) return suggested.map((p) => ({ durationMinutes: p.durationMinutes, price: p.price ?? 0 }));
  return [{ durationMinutes: 60, price: 30000 }];
}

/** A warning for an easy-to-guess game password, or null. Only a hint: the game decides what it accepts. */
function passwordWeakness(p: string): string | null {
  if (!p) return null;
  const kinds = [/[a-z]/, /[A-Z]/, /\d/, /[^A-Za-z0-9]/].filter((r) => r.test(p)).length;
  if (p.length < 10 || kinds < 3) {
    return 'Mật khẩu này dễ đoán. Nên dùng từ 10 ký tự, có chữ hoa, chữ thường, số và ký hiệu — hoặc bấm “Gợi ý mật khẩu”.';
  }
  return null;
}

/** Account states in which an ACTIVE listing is hidden from search until the owner changes the password. */
const HIDDEN_UNTIL_ROTATED = new Set(['ROTATION_PENDING', 'ROTATION_OVERDUE', 'AWAITING_RECLAIM']);

export function MyListingsPage() {
  const [games, setGames] = useState<GameView[]>([]);
  const flash = useFlash();
  const onListError = (err: unknown) => flash.setError(errorMessage(err));

  // Accounts and listings are paged separately (an owner may have many). Each account has at most one
  // listing, so a page of accounts needs at most one listing each, and a page of listings one account each.
  const accountPage = usePaged<GameAccount>('/game-accounts', { onError: onListError });
  const listingPage = usePaged<Listing>('/listings/mine', { onError: onListError });
  const unlistedPage = usePaged<GameAccount>('/game-accounts?unlisted=true', { size: 100, onError: onListError });
  const accounts = accountPage.items ?? [];
  const listings = listingPage.items ?? [];
  const [accountListings, setAccountListings] = useState<Listing[]>([]);
  const [listingAccounts, setListingAccounts] = useState<GameAccount[]>([]);

  const accountIdsKey = accounts.map((a) => a.id).join(',');
  useEffect(() => {
    if (!accountIdsKey) return setAccountListings([]);
    const q = accountIdsKey.split(',').map((id) => `gameAccountId=${id}`).join('&');
    api<PageResponse<Listing>>(`/listings/mine?${q}&size=100`)
      .then((r) => setAccountListings(r.content))
      .catch(onListError);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [accountIdsKey, listingPage.result]);

  const listingAccountIdsKey = Array.from(new Set(listings.map((l) => l.gameAccountId))).join(',');
  useEffect(() => {
    if (!listingAccountIdsKey) return setListingAccounts([]);
    const q = listingAccountIdsKey.split(',').map((id) => `id=${id}`).join('&');
    api<PageResponse<GameAccount>>(`/game-accounts?${q}&size=100`)
      .then((r) => setListingAccounts(r.content))
      .catch(onListError);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [listingAccountIdsKey, accountPage.result]);

  const reload = useCallback(() => {
    accountPage.reload();
    listingPage.reload();
    unlistedPage.reload();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    api<GameView[]>('/games')
      .then(setGames)
      .catch((err) => flash.setError(`Không tải được danh sách game: ${errorMessage(err)}`));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const gameName = (id: string) => games.find((g) => g.id === id)?.name ?? id.slice(0, 8);

  return (
    <div className="stack">
      <h1>Account cho thuê</h1>
      <PageHelp id="my-listings" title="Cho thuê account trong 4 bước">
        <ol>
          <li>
            <strong>Thêm account game</strong> ở phần đầu trang. Mật khẩu được mã hóa, chỉ người thuê thấy trong giờ thuê.
          </li>
          <li>Chờ VieGG xác minh thông tin khôi phục (email, số điện thoại) của account.</li>
          <li>
            <strong>Tạo bài đăng</strong>: chọn account, đọc các rủi ro của game, rồi đặt giá và tiền cọc theo rủi ro lớn nhất.
          </li>
          <li>
            Bấm <strong>Gửi duyệt</strong>. Được duyệt thì bài đăng hiển thị và bạn bắt đầu nhận đơn.
          </li>
        </ol>
        <p>Sau mỗi lượt thuê, nhớ bấm “Đổi mật khẩu” cho account để bảo vệ account của bạn.</p>
      </PageHelp>
      <FlashMessages error={flash.error} notice={flash.notice} />

      <section id="game-accounts" className="card stack">
        <h2>Account game của bạn</h2>
        <p className="muted">
          Thông tin đăng nhập được mã hóa khi lưu. Account phải được xác minh thông tin khôi phục trước khi đăng listing.
        </p>
        <AccountForm
          games={games}
          onCreated={() => {
            flash.setError(null);
            flash.setNotice('Đã thêm account game và gửi admin xác minh thông tin khôi phục. Sau khi được xác minh, bạn có thể gửi duyệt listing.');
            reload();
          }}
          onError={(err) => flash.setError(errorMessage(err))}
        />
        {accounts.length === 0 ? (
          <p className="muted">Chưa có account nào.</p>
        ) : (
          accounts.map((a) => (
            <AccountRow
              key={a.id}
              account={a}
              game={games.find((g) => g.id === a.gameId)}
              gameName={gameName(a.gameId)}
              listings={accountListings.filter((l) => l.gameAccountId === a.id)}
              onChanged={(msg) => {
                flash.setError(null);
                flash.setNotice(msg);
                reload();
              }}
              onError={(err) => flash.setError(errorMessage(err))}
            />
          ))
        )}
        <Pager result={accountPage.result} page={accountPage.page} onPage={accountPage.setPage} />
      </section>

      <section className="card stack">
        <h2>Tạo listing</h2>
        <ListingForm
          accounts={unlistedPage.items ?? []}
          games={games}
          listedAccountIds={new Set()}
          onDone={() => {
            flash.setError(null);
            flash.setNotice('Đã tạo listing nháp.');
            reload();
          }}
          onError={(err) => flash.setError(errorMessage(err))}
        />
      </section>

      <section id="listings" className="stack">
        <h2>Listing của bạn</h2>
        {listings.length === 0 && <p className="muted">Chưa có listing nào.</p>}
        {listings.map((l) => (
          <ListingRow
            key={l.id}
            listing={l}
            accounts={listingAccounts}
            games={games}
            onChanged={(msg) => {
              flash.setError(null);
              flash.setNotice(msg);
              reload();
            }}
            onError={(err) => flash.setError(errorMessage(err))}
          />
        ))}
        <Pager result={listingPage.result} page={listingPage.page} onPage={listingPage.setPage} />
      </section>
    </div>
  );
}

function AccountForm({
  games,
  onCreated,
  onError,
}: {
  games: GameView[];
  onCreated: () => void;
  onError: (err: unknown) => void;
}) {
  const [gameId, setGameId] = useState('');
  const [serverRegion, setServerRegion] = useState('');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [recoveryEmailOwned, setRecoveryEmailOwned] = useState(false);
  const [recoveryPhoneOwned, setRecoveryPhoneOwned] = useState(false);
  const [twoFactorOwned, setTwoFactorOwned] = useState(false);
  const [accountKey, setAccountKey] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const game = games.find((g) => g.id === gameId);
  const needsKey = !!game && game.accountKey.type !== 'LOGIN';
  const serverRequired = needsKey && game!.accountKey.perServer;

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setSubmitting(true);
    try {
      await api('/game-accounts', {
        method: 'POST',
        body: {
          gameId,
          serverRegion,
          username,
          password,
          recoveryEmailOwned,
          recoveryPhoneOwned,
          twoFactorOwned,
          accountKey: needsKey ? accountKey : undefined,
        },
      });
      setUsername('');
      setPassword('');
      setAccountKey('');
      onCreated();
    } catch (err) {
      onError(err);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <form className="form wide" onSubmit={onSubmit}>
      <div className="grid">
        <div className="field">
          <label htmlFor="ga-game">Game</label>
          <select id="ga-game" required value={gameId} onChange={(e) => setGameId(e.target.value)}>
            <option value="">Chọn game</option>
            {games.map((g) => (
              <option key={g.id} value={g.id}>
                {g.name}
              </option>
            ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor="ga-region">Server{serverRequired ? '' : ' (không bắt buộc)'}</label>
          <input id="ga-region" required={serverRequired} maxLength={60} value={serverRegion} onChange={(e) => setServerRegion(e.target.value)} />
        </div>
        {needsKey && (
          <div className="field">
            <label htmlFor="ga-key">{game!.accountKeyLabel}</label>
            <input
              id="ga-key"
              required
              maxLength={200}
              placeholder={game!.accountKey.example ?? ''}
              value={accountKey}
              onChange={(e) => setAccountKey(e.target.value)}
              aria-describedby="ga-key-hint"
            />
            <span id="ga-key-hint" className="field-hint">
              Mỗi {game!.accountKeyLabel} chỉ được đăng một lần trên VieGG. Admin sẽ đối chiếu khi xác minh account.
            </span>
          </div>
        )}
        <div className="field">
          <label htmlFor="ga-user">Tên đăng nhập</label>
          <input id="ga-user" required maxLength={200} value={username} onChange={(e) => setUsername(e.target.value)} />
        </div>
        <div className="field">
          <label htmlFor="ga-pass">Mật khẩu</label>
          <input id="ga-pass" type="password" required maxLength={200} value={password} onChange={(e) => setPassword(e.target.value)} />
        </div>
      </div>
      <p className="field-hint">Bạn phải xác nhận cả ba mục dưới đây để thêm account.</p>
      <div className="row">
        <label className="checkbox">
          <input type="checkbox" required checked={recoveryEmailOwned} onChange={(e) => setRecoveryEmailOwned(e.target.checked)} />
          Tôi sở hữu email khôi phục
        </label>
        <label className="checkbox">
          <input type="checkbox" required checked={recoveryPhoneOwned} onChange={(e) => setRecoveryPhoneOwned(e.target.checked)} />
          Tôi sở hữu số điện thoại khôi phục
        </label>
        <label className="checkbox">
          <input type="checkbox" required checked={twoFactorOwned} onChange={(e) => setTwoFactorOwned(e.target.checked)} />
          Tôi sở hữu xác thực 2 lớp
        </label>
      </div>
      <div>
        <button className="btn btn-primary" disabled={submitting}>
          {submitting ? 'Đang lưu…' : 'Thêm account'}
        </button>
      </div>
    </form>
  );
}

function AccountRow({
  account: a,
  game,
  gameName,
  listings,
  onChanged,
  onError,
}: {
  account: GameAccount;
  game?: GameView;
  gameName: string;
  listings: Listing[];
  onChanged: (msg: string) => void;
  onError: (err: unknown) => void;
}) {
  const [rotating, setRotating] = useState(false);
  const [newUsername, setNewUsername] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [loggedOutAllDevices, setLoggedOutAllDevices] = useState(false);
  const [showPassword, setShowPassword] = useState(false);
  const [copied, setCopied] = useState(false);
  const [busy, setBusy] = useState(false);
  // The page-level flash sits at the top, far from this row, so failures are also shown here.
  const [rowError, setRowError] = useState<string | null>(null);
  const needsRotation = HIDDEN_UNTIL_ROTATED.has(a.securityState);
  const activeListings = listings.filter((l) => l.status === 'ACTIVE');
  const usesKey = !!game && game.accountKey.type !== 'LOGIN';
  const keyMissing = usesKey && !a.accountKey;
  // The owner can fill in a missing key, or fix a typo until the account is verified.
  const keyEditable = usesKey && (keyMissing || a.securityState === 'PENDING_VERIFICATION');
  const [editingKey, setEditingKey] = useState(false);
  const [keyValue, setKeyValue] = useState('');

  const onSaveKey = async (e: FormEvent) => {
    e.preventDefault();
    const ok = await act(
      () => api(`/game-accounts/${a.id}/account-key`, { method: 'PUT', body: { accountKey: keyValue } }),
      `Đã lưu ${game?.accountKeyLabel ?? 'định danh'} cho account.`,
    );
    if (ok) {
      setEditingKey(false);
      setKeyValue('');
    }
  };

  const act = async (fn: () => Promise<unknown>, msg: string): Promise<boolean> => {
    setBusy(true);
    setRowError(null);
    try {
      await fn();
      onChanged(msg);
      return true;
    } catch (err) {
      setRowError(errorMessage(err));
      onError(err);
      return false;
    } finally {
      setBusy(false);
    }
  };

  const onRotate = async (e: FormEvent) => {
    e.preventDefault();
    const ok = await act(
      () =>
        api(`/game-accounts/${a.id}/rotation`, {
          method: 'POST',
          body: { newUsername: newUsername || undefined, newPassword, loggedOutAllDevices },
        }),
      needsRotation && activeListings.length > 0
        ? 'Đã cập nhật mật khẩu mới. Listing của account đã hiển thị lại để nhận đơn.'
        : 'Đã cập nhật mật khẩu mới.',
    );
    if (ok) {
      setRotating(false);
      setNewUsername('');
      setNewPassword('');
      setShowPassword(false);
      setCopied(false);
      setLoggedOutAllDevices(false);
    }
  };

  const onSuggest = async () => {
    setRowError(null);
    try {
      const res = await api<{ password: string }>(`/game-accounts/${a.id}/password-suggestion`, { method: 'POST' });
      setNewPassword(res.password);
      setShowPassword(true);
      setCopied(false);
    } catch (err) {
      setRowError(errorMessage(err));
    }
  };

  const onCopy = async () => {
    try {
      await navigator.clipboard.writeText(newPassword);
      setCopied(true);
    } catch {
      setRowError('Không sao chép được, hãy chọn mật khẩu và sao chép thủ công.');
    }
  };

  const weak = passwordWeakness(newPassword);

  const onReclaim = () => {
    if (!window.confirm('Lấy lại account? Người thuê sẽ không còn dùng được account này.')) return;
    act(() => api(`/game-accounts/${a.id}/reclaim`, { method: 'POST' }), 'Đã lấy lại account.');
  };

  return (
    <div className="card" style={{ marginBottom: 0 }}>
      <div className="row spread">
        <div>
          <strong>{a.loginHint ?? a.id.slice(0, 8)}</strong>
          <span className="muted">
            {' '}
            · {gameName}
            {a.serverRegion ? ` · ${a.serverRegion}` : ''}
            {a.accountKey ? ` · ${game?.accountKeyLabel ?? 'ID'}: ${a.accountKey}` : ''}
          </span>
        </div>
        <div className="row">
          <span className="badge" title={hintOf(SECURITY_STATE, a.securityState)}>
            {labelOf(SECURITY_STATE, a.securityState)}
          </span>
          {a.recoveryVerifiedAt ? <span className="badge badge-ok">Đã xác minh khôi phục</span> : <span className="badge badge-warn">Chưa xác minh khôi phục</span>}
        </div>
      </div>
      {keyMissing && (
        <p className="error" style={{ marginTop: '0.4rem' }}>
          Account chưa có {game!.accountKeyLabel}. Cần bổ sung trước khi gửi duyệt listing.
        </p>
      )}
      {a.rotationDueAt && a.securityState === 'ROTATION_PENDING' && (
        <p className="error" style={{ marginTop: '0.4rem' }}>
          Cần đổi mật khẩu trước {formatDateTime(a.rotationDueAt)}, nếu không đơn kế tiếp sẽ bị hủy.
        </p>
      )}
      {a.securityState === 'ROTATION_OVERDUE' && (
        <p className="error" style={{ marginTop: '0.4rem' }}>
          Đã quá hạn đổi mật khẩu. Bấm “Đổi mật khẩu” để cập nhật mật khẩu mới.
        </p>
      )}
      {needsRotation && activeListings.length > 0 && (
        <p className="muted" style={{ marginTop: '0.4rem' }}>
          {activeListings.length} listing của account đang bị ẩn khỏi trang tìm kiếm và sẽ tự hiển thị lại ngay khi bạn đổi
          mật khẩu xong.
        </p>
      )}
      <div className="row" style={{ marginTop: '0.5rem' }}>
        <button className={needsRotation ? 'btn btn-primary' : 'btn'} disabled={busy} onClick={() => setRotating((v) => !v)}>
          {rotating ? 'Đóng' : 'Đổi mật khẩu'}
        </button>
        {a.securityState === 'AWAITING_RECLAIM' && (
          <button className="btn" disabled={busy} onClick={onReclaim}>
            Lấy lại account
          </button>
        )}
        {keyEditable && (
          <button className={keyMissing ? 'btn btn-primary' : 'btn'} disabled={busy} onClick={() => setEditingKey((v) => !v)}>
            {editingKey ? 'Đóng' : keyMissing ? `Nhập ${game!.accountKeyLabel}` : `Sửa ${game!.accountKeyLabel}`}
          </button>
        )}
      </div>
      {editingKey && keyEditable && (
        <form className="form" style={{ marginTop: '0.75rem' }} onSubmit={onSaveKey}>
          <div className="field">
            <label htmlFor={`key-${a.id}`}>{game!.accountKeyLabel}</label>
            <input
              id={`key-${a.id}`}
              required
              maxLength={200}
              placeholder={game!.accountKey.example ?? a.accountKey ?? ''}
              value={keyValue}
              onChange={(e) => setKeyValue(e.target.value)}
            />
            <span className="field-hint">Sau khi account được xác minh, chỉ hỗ trợ VieGG mới sửa được.</span>
          </div>
          <div>
            <button className="btn btn-primary" disabled={busy}>
              Lưu
            </button>
          </div>
        </form>
      )}
      {rowError && (
        <p className="error" role="alert" style={{ marginTop: '0.4rem' }}>
          {rowError}
        </p>
      )}
      {rotating && (
        <form className="form" style={{ marginTop: '0.75rem' }} onSubmit={onRotate}>
          <div className="field">
            <label htmlFor={`ru-${a.id}`}>Tên đăng nhập mới (nếu đổi)</label>
            <input id={`ru-${a.id}`} maxLength={200} value={newUsername} onChange={(e) => setNewUsername(e.target.value)} />
          </div>
          <div className="field">
            <label htmlFor={`rp-${a.id}`}>Mật khẩu mới</label>
            <input
              id={`rp-${a.id}`}
              type={showPassword ? 'text' : 'password'}
              required
              maxLength={200}
              autoComplete="new-password"
              spellCheck={false}
              value={newPassword}
              onChange={(e) => {
                setNewPassword(e.target.value);
                setCopied(false);
              }}
            />
            <div className="row" style={{ marginTop: '0.35rem' }}>
              <button type="button" className="btn" disabled={busy} onClick={onSuggest}>
                Gợi ý mật khẩu
              </button>
              <button type="button" className="btn" onClick={() => setShowPassword((v) => !v)}>
                {showPassword ? 'Ẩn' : 'Hiện'}
              </button>
              {newPassword && (
                <button type="button" className="btn" onClick={onCopy}>
                  {copied ? 'Đã sao chép' : 'Sao chép'}
                </button>
              )}
            </div>
            {weak && <span className="error">{weak}</span>}
            <span className="field-hint">
              Mật khẩu gợi ý gồm 16 ký tự ngẫu nhiên và chưa từng dùng cho account này. Hãy đổi mật khẩu trên trang của game
              trước, rồi mới bấm “Lưu mật khẩu mới”. VieGG không nhận lại mật khẩu cũ vì người thuê trước đã biết nó.
            </span>
          </div>
          <label className="checkbox">
            <input type="checkbox" required checked={loggedOutAllDevices} onChange={(e) => setLoggedOutAllDevices(e.target.checked)} />
            Tôi đã đăng xuất tất cả thiết bị khác (bắt buộc)
          </label>
          <div>
            <button className="btn btn-primary" disabled={busy}>
              Lưu mật khẩu mới
            </button>
          </div>
        </form>
      )}
    </div>
  );
}

/** Creates a listing, or edits one when `listing` is given (sends its version for optimistic locking). */
function ListingForm({
  accounts,
  games,
  listing,
  listedAccountIds,
  onDone,
  onError,
}: {
  accounts: GameAccount[];
  games: GameView[];
  listing?: Listing;
  /** Accounts that already have a listing; each account can only be listed once. */
  listedAccountIds?: Set<string>;
  onDone: () => void;
  onError: (err: unknown) => void;
}) {
  const [gameAccountId, setGameAccountId] = useState(listing?.gameAccountId ?? '');
  const [riskSeen, setRiskSeen] = useState(false);
  const [title, setTitle] = useState(listing?.title ?? '');
  const [description, setDescription] = useState(listing?.description ?? '');
  const [tiers, setTiers] = useState<PricingTier[]>(() =>
    listing?.pricingTiers?.length ? listing.pricingTiers : [{ durationMinutes: 60, price: listing?.pricePerHour ?? 30000 }],
  );
  const [depositAmount, setDepositAmount] = useState(listing?.depositAmount ?? 200000);
  const [riskValues, setRiskValues] = useState<RiskTermValues>({});
  const [ownRisks, setOwnRisks] = useState<GameRisk[]>(() => ownerRiskDrafts(listing));
  const [autoAccept, setAutoAccept] = useState(listing?.autoAccept ?? false);
  const [endMode, setEndMode] = useState<EndMode>(listing?.endMode ?? 'FORCE_AT_END');
  // null = not chosen yet: shows (and saves) the game's default.
  const [freeCancel, setFreeCancel] = useState<number | null>(listing?.renterFreeCancelMinutes ?? null);
  const [attributes, setAttributes] = useState(formatAttributes(listing?.attributes));
  const [submitting, setSubmitting] = useState(false);

  const gameIdOf = (accountId: string) => accounts.find((a) => a.id === accountId)?.gameId;
  const game = games.find((g) => g.id === (listing?.gameId ?? gameIdOf(gameAccountId)));
  // The game's risks plus the ones the owner adds: deposit and compensation are set on all of them.
  const risky = withOwnerRisks(game, ownRisks);
  const ownRisksInvalid = ownRisks.some((r) => !r.name.trim()) || !!duplicateOwnerRisk(game, ownRisks);
  const sfx = listing?.id ?? 'new';
  const freeCancelValue = freeCancel ?? game?.renterFreeCancelMinutes ?? 120;
  const freeCancelChoices = FREE_CANCEL_CHOICES.includes(freeCancelValue)
    ? FREE_CANCEL_CHOICES
    : [...FREE_CANCEL_CHOICES, freeCancelValue].sort((a, b) => a - b);

  const chooseAccount = (id: string) => {
    setGameAccountId(id);
    // Another game means another risk: the owner has to read and confirm it again.
    setRiskSeen(false);
    const nextGame = games.find((g) => g.id === gameIdOf(id));
    if (nextGame?.id !== game?.id) {
      setRiskValues({});
      setOwnRisks([]);
      setTiers(initialTiers(nextGame));
    }
  };

  // With owner risks the deposit is the sum of the per-risk deposits; otherwise the single field below.
  const riskTotal = riskDepositTotal(risky, riskValues, listing);
  const effectiveDeposit = riskTotal ?? depositAmount;
  const missingAmounts = missingRiskAmounts(risky, riskValues, listing);
  const depositOutOfRange =
    !!game &&
    (missingAmounts.length > 0 ||
      noOwnerRiskCovered(risky, riskValues, listing) ||
      effectiveDeposit < game.depositMin ||
      effectiveDeposit > game.depositMax);
  const suggested = game?.suggestedPackages ?? [];
  const toggleSuggested = (durationMinutes: number, price: number | null, on: boolean) =>
    setTiers((ts) =>
      on
        ? ts.some((t) => t.durationMinutes === durationMinutes)
          ? ts
          : [...ts, { durationMinutes, price: price ?? 0 }].sort((a, b) => a.durationMinutes - b.durationMinutes)
        : ts.filter((t) => t.durationMinutes !== durationMinutes),
    );
  const durationChoices = TIER_DURATIONS.filter((d) => !game || (d >= game.minDurationMinutes && d <= game.maxDurationMinutes));
  const tierDurations = tiers.map((t) => t.durationMinutes);
  const tiersInvalid =
    tiers.length === 0 ||
    tiers.some((t) => !(t.price > 0)) ||
    new Set(tierDurations).size !== tiers.length ||
    (!!game && tiers.some((t) => t.durationMinutes < game.minDurationMinutes || t.durationMinutes > game.maxDurationMinutes));
  const updateTier = (i: number, patch: Partial<PricingTier>) => setTiers((ts) => ts.map((t, j) => (j === i ? { ...t, ...patch } : t)));
  const addTier = () => {
    const next = durationChoices.find((d) => !tierDurations.includes(d)) ?? durationChoices[durationChoices.length - 1] ?? 60;
    setTiers((ts) => [...ts, { durationMinutes: next, price: 0 }]);
  };

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setSubmitting(true);
    const pricingTiers = [...tiers].sort((a, b) => a.durationMinutes - b.durationMinutes);
    const content = {
      title,
      description,
      attributes: parseAttributes(attributes),
      pricingTiers,
      depositAmount: effectiveDeposit,
      riskTerms: riskTermsOf(game, riskValues, listing),
      ownerRisks: ownerRisksBody(ownRisks, riskValues, listing),
      autoAccept,
      endMode,
      renterFreeCancelMinutes: freeCancelValue,
    };
    try {
      if (listing) {
        await api(`/listings/${listing.id}`, { method: 'PUT', body: { ...content, version: listing.version } });
      } else {
        await api('/listings', { method: 'POST', body: { ...content, gameAccountId } });
        setGameAccountId('');
        setRiskSeen(false);
        setTitle('');
        setDescription('');
        setRiskValues({});
        setOwnRisks([]);
      }
      onDone();
    } catch (err) {
      onError(err);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <form className="form wide" onSubmit={onSubmit}>
      {!listing && (
        <div className="field" style={{ maxWidth: 480 }}>
          <label htmlFor="l-account">Bước 1 · Chọn account muốn cho thuê</label>
          <select id="l-account" required value={gameAccountId} onChange={(e) => chooseAccount(e.target.value)}>
            <option value="">Chọn account</option>
            {accounts.map((a) => {
              const listed = listedAccountIds?.has(a.id) ?? false;
              return (
                <option key={a.id} value={a.id} disabled={listed}>
                  {a.loginHint ?? a.id.slice(0, 8)} · {games.find((g) => g.id === a.gameId)?.name ?? 'Game'} ·{' '}
                  {listed ? 'Đã có listing' : labelOf(SECURITY_STATE, a.securityState)}
                </option>
              );
            })}
          </select>
          <span className="field-hint">Sau khi chọn, bạn sẽ thấy các rủi ro khi cho thuê account của game này.</span>
        </div>
      )}

      {!listing && !gameAccountId && <p className="muted">Chọn account để xem rủi ro của game trước khi đặt giá và tiền cọc.</p>}

      {game && (
        <>
          {!listing && <strong>Bước 2 · Xem rủi ro của game</strong>}
          <GameRiskPanel game={game} />
          {!listing && (
            <label className="checkbox">
              <input type="checkbox" required checked={riskSeen} onChange={(e) => setRiskSeen(e.target.checked)} />
              Tôi đã đọc và hiểu rủi ro khi cho thuê account của game này
            </label>
          )}
        </>
      )}

      {(listing || gameAccountId) && (
      <>
      {!listing && <strong>Bước 3 · Thông tin bài đăng</strong>}
      <div className="grid">
        <div className="field">
          <label htmlFor={`l-title-${listing?.id ?? 'new'}`}>Tiêu đề</label>
          <input id={`l-title-${listing?.id ?? 'new'}`} required maxLength={150} value={title} onChange={(e) => setTitle(e.target.value)} />
        </div>
        {riskTotal == null && (
        <div className="field">
          <label htmlFor={`l-deposit-${listing?.id ?? 'new'}`}>Tiền cọc (đ)</label>
          <input
            id={`l-deposit-${listing?.id ?? 'new'}`}
            type="number"
            required
            min={game?.depositMin ?? 1}
            max={game?.depositMax}
            value={depositAmount}
            aria-describedby={`l-deposit-hint-${sfx}`}
            onChange={(e) => setDepositAmount(Number(e.target.value))}
          />
          <span id={`l-deposit-hint-${sfx}`} className={depositOutOfRange ? 'field-error' : 'field-hint'}>
            {game
              ? `Từ ${formatVnd(game.depositMin)} đến ${formatVnd(game.depositMax)}.`
              : 'Phải nằm trong khoảng cọc cho phép của game.'}
          </span>
        </div>
        )}
        <div className="field">
          <label htmlFor={`l-end-${sfx}`}>Cách kết thúc thuê</label>
          <select id={`l-end-${sfx}`} value={endMode} onChange={(e) => setEndMode(e.target.value as EndMode)}>
            {END_MODES.map((m) => (
              <option key={m.value} value={m.value}>
                {m.label}
              </option>
            ))}
          </select>
          <span className="field-hint">{hintOf(END_MODE, endMode)}</span>
        </div>
        <div className="field">
          <label htmlFor={`l-freecancel-${sfx}`}>Người thuê được hủy miễn phí</label>
          <select
            id={`l-freecancel-${sfx}`}
            value={freeCancelValue}
            aria-describedby={`l-freecancel-hint-${sfx}`}
            onChange={(e) => setFreeCancel(Number(e.target.value))}
          >
            {freeCancelChoices.map((m) => (
              <option key={m} value={m}>
                {m === 0 ? 'Bất cứ lúc nào trước giờ bắt đầu' : `Trước giờ bắt đầu ít nhất ${durationLabel(m)}`}
                {game && m === game.renterFreeCancelMinutes ? ' (mặc định)' : ''}
              </option>
            ))}
          </select>
          <span id={`l-freecancel-hint-${sfx}`} className="field-hint">
            Hủy trong thời hạn này: người thuê được hoàn 100% tiền thuê.
            {freeCancelValue > 0 &&
              (game
                ? ` Hủy muộn hơn: hoàn ${game.lateCancelRefundBps / 100}%, phần còn lại về bạn.`
                : ' Hủy muộn hơn: chỉ hoàn một phần tiền thuê theo quy định của game.')}{' '}
            Tiền cọc luôn được hoàn. Đơn đã đặt giữ nguyên điều kiện cũ.
          </span>
        </div>
      </div>
      {game && (
        <OwnerRisksEditor
          game={game}
          drafts={ownRisks}
          onChange={setOwnRisks}
          onAdded={(id) => setRiskValues((v) => ({ ...v, [id]: { on: true, amount: '' } }))}
          idPrefix={`l-${sfx}`}
        />
      )}
      {risky && risky.risks.length > 0 && (
        <RiskTermsFields game={risky} values={riskValues} onChange={setRiskValues} listing={listing} idPrefix={`l-${sfx}`} />
      )}
      <fieldset className="field" style={{ border: 0, padding: 0, margin: 0 }}>
        <legend>Gói thuê và giá</legend>
        {suggested.length > 0 && (
          <div className="suggested-packages">
            <span className="field-hint">Gói gợi ý của VieGG cho game này — chọn gói bạn muốn cho thuê:</span>
            <div className="chips">
              {suggested.map((p) => {
                const on = tierDurations.includes(p.durationMinutes);
                return (
                  <label key={p.durationMinutes} className={`chip${on ? ' active' : ''}`}>
                    <input
                      type="checkbox"
                      className="sr-only"
                      checked={on}
                      disabled={!on && tiers.length >= 10}
                      onChange={(e) => toggleSuggested(p.durationMinutes, p.price, e.target.checked)}
                    />
                    {on ? '✓ ' : '+ '}
                    {durationLabel(p.durationMinutes)}
                    {p.price != null && ` · ${formatVnd(p.price)}`}
                  </label>
                );
              })}
            </div>
          </div>
        )}
        {tiers.map((t, i) => (
          <div className="row" key={i}>
            <select
              aria-label={`Thời lượng gói ${i + 1}`}
              value={t.durationMinutes}
              onChange={(e) => updateTier(i, { durationMinutes: Number(e.target.value) })}
            >
              {(durationChoices.includes(t.durationMinutes) ? durationChoices : [t.durationMinutes, ...durationChoices]).map((d) => (
                <option key={d} value={d}>
                  {durationLabel(d)}
                </option>
              ))}
            </select>
            <input
              aria-label={`Giá gói ${i + 1} (đ)`}
              type="number"
              required
              min={1}
              value={t.price || ''}
              placeholder="Giá (đ)"
              onChange={(e) => updateTier(i, { price: Number(e.target.value) })}
            />
            {tiers.length > 1 && (
              <button type="button" className="btn" onClick={() => setTiers((ts) => ts.filter((_, j) => j !== i))}>
                Xóa
              </button>
            )}
            {(() => {
              const s = suggested.find((p) => p.durationMinutes === t.durationMinutes);
              if (!s) return <span className="field-hint">Gói tự thêm</span>;
              return (
                <span className="field-hint">
                  Gói gợi ý{s.price != null && s.price !== t.price ? ` (giá gợi ý ${formatVnd(s.price)})` : ''}
                </span>
              );
            })()}
          </div>
        ))}
        <div>
          <button type="button" className="btn" disabled={tiers.length >= 10} onClick={addTier}>
            + Thêm gói tự chọn
          </button>
        </div>
        <span className={tiersInvalid ? 'field-error' : 'field-hint'}>
          {tiers.length === 0
            ? 'Cần ít nhất một gói thuê: chọn gói gợi ý hoặc thêm gói tự chọn.'
            : new Set(tierDurations).size !== tiers.length
            ? 'Mỗi gói phải có thời lượng khác nhau.'
            : 'Ví dụ: 1 giờ 30.000đ, 3 giờ 80.000đ, 1 ngày 200.000đ. Tối đa 10 gói.'}
        </span>
      </fieldset>
      <div className="field">
        <label htmlFor={`l-desc-${listing?.id ?? 'new'}`}>Mô tả</label>
        <textarea id={`l-desc-${listing?.id ?? 'new'}`} maxLength={5000} value={description} onChange={(e) => setDescription(e.target.value)} />
      </div>
      <div className="field">
        <label htmlFor={`l-attrs-${listing?.id ?? 'new'}`}>Thông tin nổi bật (mỗi dòng một mục, dạng "tên: giá trị")</label>
        <textarea
          id={`l-attrs-${listing?.id ?? 'new'}`}
          placeholder={'Rank: Kim Cương\nServer: VN\nTướng: 120\nTrang phục: 80'}
          value={attributes}
          onChange={(e) => setAttributes(e.target.value)}
        />
        <span className="field-hint">Hiện dưới dạng thẻ trên trang tìm kiếm, giúp người thuê chọn nhanh.</span>
      </div>
      <label className="checkbox">
        <input type="checkbox" checked={autoAccept} onChange={(e) => setAutoAccept(e.target.checked)} />
        Tự động nhận đơn (không cần duyệt từng đơn)
      </label>
      {listing?.status === 'ACTIVE' && (
        <p className="muted">Đổi giá, cọc, tự nhận đơn được áp dụng ngay. Chỉ sửa tiêu đề, mô tả, thông tin account hoặc thêm ảnh mới phải duyệt lại.</p>
      )}
      <div>
        <button className="btn btn-primary" disabled={submitting || depositOutOfRange || tiersInvalid || ownRisksInvalid || (!listing && (!gameAccountId || (!!game && !riskSeen)))}>
          {submitting ? 'Đang lưu…' : listing ? 'Lưu thay đổi' : 'Tạo nháp'}
        </button>
      </div>
      </>
      )}
    </form>
  );
}

function ListingRow({
  listing: l,
  accounts,
  games,
  onChanged,
  onError,
}: {
  listing: Listing;
  accounts: GameAccount[];
  games: GameView[];
  onChanged: (msg: string) => void;
  onError: (err: unknown) => void;
}) {
  const [busy, setBusy] = useState(false);
  const [editing, setEditing] = useState(false);
  const [showBusy, setShowBusy] = useState(false);
  // The page-level flash sits at the top, far from this row, so failures are also shown here.
  const [rowError, setRowError] = useState<string | null>(null);

  const act = async (path: string, msg: string) => {
    setBusy(true);
    setRowError(null);
    try {
      await api(`/listings/${l.id}${path}`, { method: 'POST' });
      onChanged(msg);
    } catch (err) {
      setRowError(errorMessage(err));
      onError(err);
    } finally {
      setBusy(false);
    }
  };

  const canSubmit = l.status === 'DRAFT' || l.status === 'PAUSED';
  // Same checks the server runs on submit, so the owner sees why before clicking.
  // `games` only holds ALLOWED games; while it is still loading, skip the game check.
  const account = accounts.find((a) => a.id === l.gameAccountId);
  let submitBlocked: string | null = null;
  if (games.length > 0 && !games.some((g) => g.id === l.gameId)) {
    submitBlocked = 'Game của listing này hiện không được phép cho thuê, chưa thể gửi duyệt.';
  } else if (account && account.securityState === 'SUSPENDED_SECURITY_REVIEW') {
    submitBlocked = 'Account đang bị tạm khóa để rà soát bảo mật, chưa thể gửi duyệt.';
  } else if (account && (!account.recoveryVerifiedAt || account.securityState === 'PENDING_VERIFICATION')) {
    submitBlocked = 'Account đang chờ admin xác minh thông tin khôi phục. Bạn có thể gửi duyệt ngay sau khi account được xác minh.';
  }
  const canPause = l.status === 'ACTIVE';
  const hiddenUntilRotated = l.status === 'ACTIVE' && !!account && HIDDEN_UNTIL_ROTATED.has(account.securityState);
  const canEdit = l.status !== 'SUSPENDED';

  return (
    <div className="card">
      <div className="row spread">
        <div>
          <h2 style={{ fontSize: '1.05rem', marginBottom: '0.2rem' }}>{l.title}</h2>
          <p className="muted" style={{ margin: 0 }}>
            {l.pricingTiers?.length
              ? l.pricingTiers.map((t) => `${durationLabel(t.durationMinutes)} ${formatVnd(t.price)}`).join(' · ')
              : `${formatVnd(l.pricePerHour)} / giờ`}{' '}
            · cọc {formatVnd(l.depositAmount)}
          </p>
        </div>
        <StatusBadge status={l.status} />
      </div>
      {l.reviewNote && (
        <p className="error">
          {l.status === 'SUSPENDED' ? 'Lý do đình chỉ' : 'Lý do từ chối'}: {l.reviewNote}
        </p>
      )}
      <div style={{ marginTop: '0.6rem' }}>
        <ListingImageManager listing={l} onChanged={onChanged} onError={onError} />
      </div>
      <div className="row" style={{ marginTop: '0.6rem' }}>
        {canSubmit && (
          <button
            className="btn btn-primary"
            disabled={busy || !!submitBlocked}
            title={submitBlocked ?? undefined}
            onClick={() => act('/submit', 'Đã gửi listing để duyệt.')}
          >
            Gửi duyệt
          </button>
        )}
        {canPause && (
          <button className="btn" disabled={busy} onClick={() => act('/pause', 'Đã tạm dừng listing.')}>
            Tạm dừng
          </button>
        )}
        {canEdit && (
          <button className="btn" disabled={busy} onClick={() => setEditing((v) => !v)}>
            {editing ? 'Đóng' : 'Sửa'}
          </button>
        )}
        {canEdit && (
          <button className="btn" aria-expanded={showBusy} onClick={() => setShowBusy((v) => !v)}>
            {showBusy ? 'Đóng lịch bận' : 'Lịch bận'}
          </button>
        )}
      </div>
      {showBusy && (
        <div style={{ marginTop: '0.75rem' }}>
          <h3 style={{ fontSize: '0.95rem', margin: '0 0 0.3rem' }}>Giờ bạn bận (không cho thuê)</h3>
          <BusyBlocksManager listing={l} />
        </div>
      )}
      {hiddenUntilRotated && (
        <p className="error" style={{ marginTop: '0.4rem' }}>
          Listing đang bị ẩn vì account chưa đổi mật khẩu sau lượt thuê. Đổi mật khẩu ở mục “Account game của bạn”, listing sẽ tự
          hiển thị lại.
        </p>
      )}
      {canSubmit && submitBlocked && (
        <p className="muted" style={{ marginTop: '0.4rem' }}>
          {submitBlocked}
        </p>
      )}
      {rowError && (
        <p className="error" role="alert" style={{ marginTop: '0.4rem' }}>
          {rowError}
        </p>
      )}
      {editing && (
        <div style={{ marginTop: '0.75rem' }}>
          <ListingForm
            accounts={accounts}
            games={games}
            listing={l}
            onDone={() => {
              setEditing(false);
              onChanged('Đã lưu listing.');
            }}
            onError={onError}
          />
          {l.status === 'REJECTED' && <p className="muted">Sau khi sửa, listing về nháp. Hãy gửi duyệt lại.</p>}
        </div>
      )}
    </div>
  );
}
