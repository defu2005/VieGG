import { useEffect, useState } from 'react';
import { formatDateTime } from '../lib/format';
import { nowMs } from '../lib/serverClock';

function remaining(until: string): number {
  return Math.max(0, new Date(until).getTime() - nowMs());
}

/** Live mm:ss / h:mm:ss countdown, "X ngày Y giờ" from one day up; shows "Hết hạn" at zero. */
export function Countdown({ until, onExpire }: { until: string; onExpire?: () => void }) {
  const [ms, setMs] = useState(() => remaining(until));

  useEffect(() => {
    setMs(remaining(until));
    const timer = setInterval(() => {
      const left = remaining(until);
      setMs(left);
      if (left === 0) {
        clearInterval(timer);
        onExpire?.();
      }
    }, 1000);
    return () => clearInterval(timer);
    // onExpire is intentionally not a dependency: callers pass inline functions.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [until]);

  if (ms === 0) return <span className="error">Hết hạn</span>;
  const total = Math.floor(ms / 1000);
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  const pad = (n: number) => String(n).padStart(2, '0');
  // A day or more: "2 ngày 5 giờ" reads better than "53:12:40"; the exact deadline is in the tooltip.
  if (h >= 24) {
    const d = Math.floor(h / 24);
    const rh = h % 24;
    return <strong title={formatDateTime(until)}>{rh > 0 ? `${d} ngày ${rh} giờ` : `${d} ngày`}</strong>;
  }
  return <strong title={formatDateTime(until)}>{h > 0 ? `${h}:${pad(m)}:${pad(s)}` : `${pad(m)}:${pad(s)}`}</strong>;
}
