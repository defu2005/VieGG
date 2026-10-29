import { useEffect, useState } from 'react';
import { api } from '../api/client';

const EVENT = 'rentgg:notifications-changed';

/** Tells the nav badge to refetch (call after marking notifications read). */
export function notifyUnreadChanged() {
  window.dispatchEvent(new Event(EVENT));
}

/** Unread notification count for the nav; refreshes on page change, on notifyUnreadChanged and every minute. */
export function useUnreadCount(enabled: boolean, pathname: string): number {
  const [count, setCount] = useState(0);

  useEffect(() => {
    if (!enabled) {
      setCount(0);
      return;
    }
    let cancelled = false;
    const refresh = () =>
      api<{ count: number }>('/notifications/unread-count')
        .then((r) => !cancelled && setCount(r.count))
        .catch(() => undefined);
    refresh();
    const timer = setInterval(refresh, 60_000);
    window.addEventListener(EVENT, refresh);
    return () => {
      cancelled = true;
      clearInterval(timer);
      window.removeEventListener(EVENT, refresh);
    };
  }, [enabled, pathname]);

  return count;
}

/**
 * After navigating to a link with a #hash, waits for that section to render (pages load data first),
 * scrolls to it and flashes it briefly.
 */
export function useScrollToHash(pathname: string, hash: string) {
  useEffect(() => {
    if (!hash) return;
    const id = decodeURIComponent(hash.slice(1));
    let tries = 0;
    const timer = setInterval(() => {
      const el = document.getElementById(id);
      if (el) {
        clearInterval(timer);
        el.scrollIntoView({ behavior: 'smooth', block: 'start' });
        el.classList.add('flash-target');
        setTimeout(() => el.classList.remove('flash-target'), 2000);
      } else if (++tries > 40) {
        clearInterval(timer);
      }
    }, 100);
    return () => clearInterval(timer);
  }, [pathname, hash]);
}
