import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import type { Balances } from '../api/types';
import { formatVnd } from '../lib/format';

/**
 * Wallet balance next to a "pay" button: payments are taken from the wallet, so show whether it covers
 * {@code amount} and link to the top-up form when it does not. {@code refreshKey} reloads the balance.
 */
export function WalletBalanceLine({ amount, refreshKey }: { amount: number; refreshKey?: unknown }) {
  const [balance, setBalance] = useState<number | null>(null);

  useEffect(() => {
    api<Balances>('/wallet/balances')
      .then((b) => setBalance(b.available))
      .catch(() => setBalance(null));
  }, [refreshKey]);

  if (balance == null) return null;
  const shortfall = amount - balance;
  return (
    <p className="muted" style={{ margin: 0 }}>
      Thanh toán bằng số dư ví · Số dư hiện tại: <strong>{formatVnd(balance)}</strong>
      {shortfall > 0 && (
        <>
          {' '}
          — còn thiếu <strong className="error">{formatVnd(shortfall)}</strong>.{' '}
          <Link to={`/wallet?topup=${shortfall}#topup`}>Nạp tiền vào ví</Link>
        </>
      )}
    </p>
  );
}
