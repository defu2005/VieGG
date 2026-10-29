import { useEffect, useState } from 'react';
import { api } from '../api/client';
import type { UserBrief } from '../api/types';

/**
 * Who an id is (name + email), for staff screens that show user ids. Requests made in the same tick are
 * batched into one GET /staff/users/brief call and results are cached for the session.
 */
const cache = new Map<string, UserBrief | null>();
const waiting = new Map<string, ((b: UserBrief | null) => void)[]>();
let scheduled = false;

function flush() {
  scheduled = false;
  const ids = [...waiting.keys()];
  if (ids.length === 0) return;
  for (let i = 0; i < ids.length; i += 100) {
    const chunk = ids.slice(i, i + 100);
    api<UserBrief[]>(`/staff/users/brief?ids=${chunk.join(',')}`)
      .then((rows) => {
        const byId = new Map(rows.map((r) => [r.id, r]));
        for (const id of chunk) settle(id, byId.get(id) ?? null);
      })
      // No lookup permission or network error: callers fall back to the short id.
      .catch(() => chunk.forEach((id) => settle(id, null)));
  }
}

function settle(id: string, b: UserBrief | null) {
  cache.set(id, b);
  const cbs = waiting.get(id) ?? [];
  waiting.delete(id);
  cbs.forEach((cb) => cb(b));
}

export function loadUserBrief(id: string): Promise<UserBrief | null> {
  if (cache.has(id)) return Promise.resolve(cache.get(id) ?? null);
  return new Promise((resolve) => {
    const list = waiting.get(id);
    if (list) list.push(resolve);
    else waiting.set(id, [resolve]);
    if (!scheduled) {
      scheduled = true;
      queueMicrotask(flush);
    }
  });
}

/** Forget cached names (e.g. after a staff member renamed / changed a user). */
export function invalidateUserBrief(id?: string) {
  if (id) cache.delete(id);
  else cache.clear();
}

/** `undefined` while loading, `null` when unknown / not allowed. */
export function useUserBrief(id: string | null | undefined, enabled = true): UserBrief | null | undefined {
  const [brief, setBrief] = useState<UserBrief | null | undefined>(() => (id && cache.has(id) ? cache.get(id) : undefined));
  useEffect(() => {
    if (!id || !enabled) {
      setBrief(null);
      return;
    }
    let alive = true;
    if (cache.has(id)) setBrief(cache.get(id) ?? null);
    else {
      setBrief(undefined);
      loadUserBrief(id).then((b) => alive && setBrief(b));
    }
    return () => {
      alive = false;
    };
  }, [id, enabled]);
  return brief;
}

/** Permissions that open GET /staff/users (mirror of StaffUserController.ANY_STAFF_LOOKUP). */
export const USER_LOOKUP_PERMISSIONS = [
  'user:read',
  'role:read',
  'role:assign',
  'withdrawal:list_all',
  'ledger:read',
  'dispute:read_any',
  'order:read_any',
  'listing:review_queue',
];
