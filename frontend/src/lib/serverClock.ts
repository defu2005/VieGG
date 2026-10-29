/**
 * "Now" as the server sees it. In dev the backend clock can be moved (dev time travel), so countdowns and
 * date pickers use this instead of Date.now(). In production builds the offset is always 0.
 */
const OFFSET_KEY = 'rentgg.devClockOffsetMs';

function readOffset(): number {
  if (!import.meta.env.DEV) return 0;
  try {
    return Number(localStorage.getItem(OFFSET_KEY)) || 0;
  } catch {
    return 0;
  }
}

let offsetMs = readOffset();

export function nowMs(): number {
  return Date.now() + offsetMs;
}

export function serverOffsetMs(): number {
  return offsetMs;
}

/** Called with the server's current time after a status check or a time travel. */
export function syncServerNow(serverNowIso: string): void {
  if (!import.meta.env.DEV) return;
  const diff = new Date(serverNowIso).getTime() - Date.now();
  // Ignore request latency noise around real time.
  offsetMs = Math.abs(diff) < 5000 ? 0 : diff;
  try {
    localStorage.setItem(OFFSET_KEY, String(offsetMs));
  } catch {
    // Storage blocked: the offset lives in memory for this tab only.
  }
}
