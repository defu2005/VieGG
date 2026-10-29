import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { api, newIdempotencyKey } from '../api/client';
import type { Balances, Deposit, PaymentProvider, WalletEntry, WalletTopup, Withdrawal } from '../api/types';
import { errorMessage, formatDateTime, formatVnd } from '../lib/format';
import { PageHelp } from '../components/PageHelp';
import { Pager, usePaged } from '../components/Pager';
import { StatusBadge } from '../components/StatusBadge';
import { DEPOSIT_STATUS, WALLET_TX_TYPE, WITHDRAWAL_STATUS } from '../lib/labels';
import { FlashMessages, useFlash } from '../lib/useFlash';
import { useSiteSettings } from '../lib/siteSettings';

const TOPUP_PRESETS = [50_000, 100_000, 200_000, 500_000, 1_000_000];
const MIN_TOPUP = 10_000;

export function WalletPage() {
  const [params] = useSearchParams();
  const site = useSiteSettings();
  const [balances, setBalances] = useState<Balances | null>(null);
  const [topups, setTopups] = useState<WalletTopup[]>([]);
  const flash = useFlash();
  const onListError = (err: unknown) => flash.setError(errorMessage(err));
  const history = usePaged<WalletEntry>('/wallet/transactions', { onError: onListError });
  const withdrawalList = usePaged<Withdrawal>('/wallet/withdrawals', { size: 10, onError: onListError });
  const depositList = usePaged<Deposit>('/wallet/deposits', { size: 10, onError: onListError });
  const entries = history.items ?? [];
  const withdrawals = withdrawalList.items ?? [];
  const deposits = depositList.items ?? [];

  // "?topup=123000" when sent here from a payment that lacked balance.
  const [topupAmount, setTopupAmount] = useState(() => {
    const wanted = Math.ceil(Number(params.get('topup')) / 1000) * 1000;
    return wanted > 0 ? Math.max(MIN_TOPUP, wanted) : 100_000;
  });

  const [providers, setProviders] = useState<PaymentProvider[]>([]);
  const [provider, setProvider] = useState<string>('');

  const [amount, setAmount] = useState(0);
  const [bankName, setBankName] = useState('');
  const [accountNumber, setAccountNumber] = useState('');
  const [accountHolder, setAccountHolder] = useState('');

  const load = useCallback(async () => {
    try {
      const [b, t] = await Promise.all([api<Balances>('/wallet/balances'), api<WalletTopup[]>('/wallet/topups?size=10')]);
      setBalances(b);
      setTopups(t);
    } catch (err) {
      flash.setError(errorMessage(err));
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  useEffect(() => {
    api<PaymentProvider[]>('/payments/providers')
      .then((list) => {
        setProviders(list);
        setProvider((current) => current || list[0]?.code || '');
      })
      .catch((err) => flash.setError(errorMessage(err)));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Back from a provider's page: /wallet?paymentResult=SUCCEEDED&payment=<id>.
  const paymentResult = params.get('paymentResult');
  useEffect(() => {
    if (!paymentResult) return;
    if (paymentResult === 'SUCCEEDED') flash.setNotice('Nạp tiền thành công, số dư ví đã được cập nhật.');
    else if (paymentResult === 'FAILED') flash.setError('Thanh toán không thành công hoặc đã bị hủy.');
    else flash.setNotice('Đang chờ cổng thanh toán xác nhận. Số dư sẽ tự cập nhật khi có kết quả.');
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [paymentResult]);

  const providerName = (code: string) => providers.find((p) => p.code === code)?.name ?? code;

  /** After a top-up or withdrawal: balances plus the lists that may have changed. */
  const refresh = () => {
    load();
    history.reload();
    withdrawalList.reload();
  };

  const onTopup = async (e: FormEvent) => {
    e.preventDefault();
    let created: WalletTopup | null = null;
    const isFake = provider === 'FAKE';
    const ok = await flash.run(async () => {
      created = await api<WalletTopup>('/wallet/topups', {
        method: 'POST',
        body: { amount: topupAmount, provider: provider || undefined },
        idempotencyKey: newIdempotencyKey(),
      });
    }, isFake ? 'Đã tạo lệnh nạp. Bấm “Giả lập thanh toán” để hoàn tất (dev).' : 'Đã tạo lệnh nạp, đang chuyển tới trang thanh toán…');
    const url = (created as WalletTopup | null)?.checkoutUrl;
    if (ok && url && !isFake) {
      // Same tab: the provider sends the payer back to /wallet with the result.
      window.location.assign(url);
      return;
    }
    if (ok) refresh();
  };

  const onSimulateTopup = async (t: WalletTopup) => {
    const ok = await flash.run(
      () => api(`/dev/payments/${t.id}/simulate-success`, { method: 'POST' }),
      `Đã nạp ${formatVnd(t.amount)} vào ví (dev).`,
    );
    if (ok) refresh();
  };

  const onWithdraw = async (e: FormEvent) => {
    e.preventDefault();
    const ok = await flash.run(
      () =>
        api('/wallet/withdrawals', {
          method: 'POST',
          body: { amount, bankName, bankAccountNumber: accountNumber, bankAccountName: accountHolder },
          idempotencyKey: newIdempotencyKey(),
        }),
      'Đã gửi yêu cầu rút tiền.',
    );
    if (ok) {
      setAmount(0);
      refresh();
    }
  };

  const withdrawEnabled = site.bool('withdrawal.enabled');
  const minWithdraw = site.num('withdrawal.min_amount');
  const maxWithdraw = site.num('withdrawal.max_amount');
  const withdrawNotice = site.get('withdrawal.notice').trim();
  const amountError = !amount
    ? null
    : amount < minWithdraw
      ? `Số tiền rút tối thiểu là ${formatVnd(minWithdraw)}.`
      : maxWithdraw > 0 && amount > maxWithdraw
        ? `Mỗi lần rút tối đa ${formatVnd(maxWithdraw)}.`
        : balances && amount > balances.available
          ? 'Số dư ví không đủ.'
          : null;

  const openTopups = topups.filter((t) => t.status === 'CREATED');
  const hasSellerHolds = balances && (balances.reviewHold > 0 || balances.disputeHold > 0);

  return (
    <div className="stack">
      <h1>Ví</h1>
      <PageHelp id="wallet" title="Ví của bạn">
        <p>Mọi giao dịch trên VieGG đi qua ví: nạp tiền vào ví để thuê, tiền thuê và tiền cọc được trừ từ ví khi đặt đơn, mọi khoản hoàn (cọc, hủy đơn, khiếu nại) quay về ví.</p>
        <p>Khi cho thuê, doanh thu được giữ một thời gian sau mỗi lượt thuê (để xử lý khiếu nại nếu có) rồi cộng vào ví. Bạn rút số dư ví về ngân hàng bằng form bên dưới.</p>
      </PageHelp>
      <FlashMessages error={flash.error} notice={flash.notice} />

      {balances && (
        <section className="grid">
          <Stat label="Số dư ví" value={formatVnd(balances.available)} hint="Dùng để thuê hoặc rút về ngân hàng." strong />
          {hasSellerHolds && (
            <>
              <Stat label="Doanh thu đang giữ (chờ đối soát)" value={formatVnd(balances.reviewHold)} hint="Lượt cho thuê vừa xong, sẽ tự cộng vào ví." />
              <Stat label="Doanh thu đang giữ (khiếu nại)" value={formatVnd(balances.disputeHold)} hint="Đơn đang có khiếu nại, chờ kết quả phân xử." />
            </>
          )}
          {balances.payoutProcessing > 0 && (
            <Stat label="Đang chuyển khoản" value={formatVnd(balances.payoutProcessing)} hint="Yêu cầu rút tiền đang được xử lý." />
          )}
        </section>
      )}

      <section id="topup" className="card stack">
        <h2>Nạp tiền</h2>
        <form className="form" onSubmit={onTopup}>
          <div className="chips" role="group" aria-label="Chọn nhanh số tiền nạp">
            {TOPUP_PRESETS.map((v) => (
              <button
                type="button"
                key={v}
                className={`chip ${topupAmount === v ? 'active' : ''}`}
                aria-pressed={topupAmount === v}
                onClick={() => setTopupAmount(v)}
              >
                {formatVnd(v)}
              </button>
            ))}
          </div>
          <div className="field">
            <label htmlFor="topup-amount">Số tiền nạp (đ)</label>
            <input
              id="topup-amount"
              type="number"
              min={MIN_TOPUP}
              step={1000}
              required
              value={topupAmount || ''}
              onChange={(e) => setTopupAmount(Number(e.target.value))}
            />
            <span className="field-hint">Tối thiểu {formatVnd(MIN_TOPUP)}. Tiền vào ví ngay khi cổng thanh toán xác nhận.</span>
          </div>
          {providers.length > 0 && (
            <div className="field">
              <span id="topup-provider-label">Phương thức thanh toán</span>
              <div className="chips" role="radiogroup" aria-labelledby="topup-provider-label">
                {providers.map((p) => (
                  <button
                    type="button"
                    key={p.code}
                    role="radio"
                    aria-checked={provider === p.code}
                    className={`chip ${provider === p.code ? 'active' : ''}`}
                    onClick={() => setProvider(p.code)}
                  >
                    {p.name}
                    {p.sandbox && p.code !== 'FAKE' ? ' · sandbox' : ''}
                  </button>
                ))}
              </div>
            </div>
          )}
          {providers.length === 0 && <p className="banner">Chưa có cổng thanh toán nào được bật.</p>}
          <div>
            <button className="btn btn-primary" disabled={flash.busy || providers.length === 0}>
              {flash.busy ? 'Đang tạo…' : `Nạp ${formatVnd(topupAmount || 0)}`}
            </button>
          </div>
        </form>
        {openTopups.length > 0 && (
          <div className="stack">
            <h3 style={{ margin: 0 }}>Lệnh nạp chờ thanh toán</h3>
            {openTopups.map((t) => (
              <div key={t.id} className="row">
                <span>
                  {formatVnd(t.amount)} · {providerName(t.provider)} · {formatDateTime(t.createdAt)}
                </span>
                {t.provider !== 'FAKE' && t.checkoutUrl && (
                  <a className="btn" href={t.checkoutUrl}>
                    Mở trang thanh toán
                  </a>
                )}
                {import.meta.env.DEV && (
                  <button className="btn" disabled={flash.busy} onClick={() => onSimulateTopup(t)}>
                    Giả lập thanh toán thành công (dev)
                  </button>
                )}
              </div>
            ))}
          </div>
        )}
      </section>

      <section id="withdraw" className="card">
        <h2>Rút tiền</h2>
        {!withdrawEnabled && <p className="banner">Tính năng rút tiền đang tạm ngưng. Vui lòng quay lại sau.</p>}
        <form className="form" onSubmit={onWithdraw}>
          <fieldset disabled={!withdrawEnabled} style={{ border: 0, padding: 0, margin: 0, display: 'contents' }}>
          <div className="field">
            <label htmlFor="amount">Số tiền (đ)</label>
            <input
              id="amount"
              type="number"
              min={Math.max(1, minWithdraw)}
              max={maxWithdraw > 0 ? maxWithdraw : undefined}
              step={1}
              required
              value={amount || ''}
              aria-invalid={amountError ? true : undefined}
              onChange={(e) => setAmount(Number(e.target.value))}
            />
            <span className="field-hint">
              Tối thiểu {formatVnd(minWithdraw)}
              {maxWithdraw > 0 ? `, tối đa ${formatVnd(maxWithdraw)} mỗi lần` : ''}; không vượt quá số dư ví
              {balances ? ` (${formatVnd(balances.available)})` : ''}.
            </span>
            {amountError && <span className="field-error">{amountError}</span>}
          </div>
          <div className="field">
            <label htmlFor="bank">Ngân hàng</label>
            <input id="bank" required maxLength={100} value={bankName} onChange={(e) => setBankName(e.target.value)} />
          </div>
          <div className="field">
            <label htmlFor="acc">Số tài khoản</label>
            <input id="acc" required pattern="[0-9]{6,20}" title="6 đến 20 chữ số" value={accountNumber} onChange={(e) => setAccountNumber(e.target.value)} />
          </div>
          <div className="field">
            <label htmlFor="holder">Chủ tài khoản</label>
            <input id="holder" required maxLength={100} value={accountHolder} onChange={(e) => setAccountHolder(e.target.value)} />
          </div>
          <div>
            <button className="btn btn-primary" disabled={flash.busy || !!amountError}>
              {flash.busy ? 'Đang gửi…' : 'Gửi yêu cầu rút'}
            </button>
          </div>
          </fieldset>
        </form>
        {withdrawNotice && (
          <p className="muted" style={{ whiteSpace: 'pre-line', marginTop: '0.75rem' }}>
            {withdrawNotice}
          </p>
        )}
      </section>

      <section id="history" className="card stack" style={{ overflowX: 'auto' }}>
        <h2>Lịch sử giao dịch ví</h2>
        {entries.length === 0 ? (
          <p className="muted">Chưa có giao dịch nào.</p>
        ) : (
          <table>
            <thead>
              <tr>
                <th>Thời gian</th>
                <th>Giao dịch</th>
                <th>Đơn</th>
                <th style={{ textAlign: 'right' }}>Số tiền</th>
              </tr>
            </thead>
            <tbody>
              {entries.map((x) => (
                <tr key={x.transactionId}>
                  <td>{formatDateTime(x.createdAt)}</td>
                  <td>{WALLET_TX_TYPE[x.type] ?? x.type}</td>
                  <td>{x.orderId ? <Link to={`/orders/${x.orderId}`}>{x.orderId.slice(0, 8)}</Link> : '—'}</td>
                  <td style={{ textAlign: 'right', fontWeight: 600 }} className={x.amount > 0 ? 'success' : undefined}>
                    {x.amount > 0 ? '+' : '−'}
                    {formatVnd(Math.abs(x.amount))}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        <Pager result={history.result} page={history.page} onPage={history.setPage} />
      </section>

      <section id="withdrawals" className="card stack" style={{ overflowX: 'auto' }}>
        <h2>Lịch sử rút tiền</h2>
        {withdrawals.length === 0 ? (
          <p className="muted">Chưa có yêu cầu rút tiền.</p>
        ) : (
          <table>
            <thead>
              <tr>
                <th>Ngày tạo</th>
                <th>Số tiền</th>
                <th>Ngân hàng</th>
                <th>Trạng thái</th>
                <th>Ghi chú</th>
              </tr>
            </thead>
            <tbody>
              {withdrawals.map((w) => (
                <tr key={w.id}>
                  <td>{formatDateTime(w.createdAt)}</td>
                  <td>{formatVnd(w.amount)}</td>
                  <td>
                    {w.bank.bankName} {w.bank.bankAccountNumber}
                  </td>
                  <td>
                    <StatusBadge status={w.status} dict={WITHDRAWAL_STATUS} />
                  </td>
                  <td>{w.failureReason ?? w.bankRef ?? '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        <Pager result={withdrawalList.result} page={withdrawalList.page} onPage={withdrawalList.setPage} />
      </section>

      <section id="deposits" className="card stack" style={{ overflowX: 'auto' }}>
        <h2>Tiền cọc của tôi (khi đi thuê)</h2>
        <p className="muted" style={{ margin: 0 }}>Cọc được giữ riêng cho từng đơn và hoàn về ví khi hết thời gian giữ cọc mà không có khiếu nại.</p>
        {deposits.length === 0 ? (
          <p className="muted">Chưa có khoản cọc nào.</p>
        ) : (
          <table>
            <thead>
              <tr>
                <th>Đơn</th>
                <th>Số tiền</th>
                <th>Trạng thái</th>
                <th>Giữ đến</th>
                <th>Đã khấu trừ</th>
                <th>Đã hoàn về ví</th>
              </tr>
            </thead>
            <tbody>
              {deposits.map((d) => (
                <tr key={d.id}>
                  <td>
                    <Link to={`/orders/${d.orderId}`}>{d.orderId.slice(0, 8)}</Link>
                  </td>
                  <td>{formatVnd(d.amount)}</td>
                  <td>
                    <StatusBadge status={d.status} dict={DEPOSIT_STATUS} />
                  </td>
                  <td>{formatDateTime(d.holdUntil)}</td>
                  <td>{formatVnd(d.deductedAmount)}</td>
                  <td>{formatVnd(d.refundedAmount)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        <Pager result={depositList.result} page={depositList.page} onPage={depositList.setPage} />
      </section>
    </div>
  );
}

function Stat({ label, value, hint, strong }: { label: string; value: string; hint: string; strong?: boolean }) {
  return (
    <div className="card" style={{ marginBottom: 0 }}>
      <p className="muted" style={{ margin: 0 }}>
        {label}
      </p>
      <p style={{ fontSize: strong ? '1.7rem' : '1.3rem', fontWeight: 700, margin: 0 }}>{value}</p>
      <p className="muted" style={{ margin: 0, fontSize: '0.82rem' }}>
        {hint}
      </p>
    </div>
  );
}
