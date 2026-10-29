import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { api, loadTokens, saveTokens, setSessionExpiredHandler } from '../api/client';
import type { Me, TokenResponse } from '../api/types';

interface AuthState {
  me: Me | null;
  loading: boolean;
  login: (email: string, password: string) => Promise<void>;
  logout: () => void;
  /** Revokes every session of this account (all devices), then logs out here. */
  logoutAll: () => Promise<void>;
  refreshMe: () => Promise<void>;
  /** Staff account: back office only, no customer features. */
  isStaff: boolean;
  /** True when the user holds at least one of the given permissions. */
  can: (...permissions: string[]) => boolean;
}

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [me, setMe] = useState<Me | null>(null);
  const [loading, setLoading] = useState(true);

  /** Drops the session locally only (used when the server already rejected it). */
  const clearSession = useCallback(() => {
    saveTokens(null);
    setMe(null);
  }, []);

  const logout = useCallback(() => {
    const tokens = loadTokens();
    clearSession();
    // Revoke this device's refresh token; the local logout must not wait for or depend on it.
    if (tokens) {
      api('/auth/logout', { method: 'POST', body: { refreshToken: tokens.refreshToken }, anonymous: true }).catch(() => {});
    }
  }, [clearSession]);

  const logoutAll = useCallback(async () => {
    await api('/me/logout-all', { method: 'POST' });
    clearSession();
  }, [clearSession]);

  const refreshMe = useCallback(async () => {
    if (!loadTokens()) {
      setMe(null);
      return;
    }
    try {
      setMe(await api<Me>('/me'));
    } catch {
      clearSession();
    }
  }, [clearSession]);

  // Restore the session from stored tokens on first load.
  useEffect(() => {
    setSessionExpiredHandler(clearSession);
    refreshMe().finally(() => setLoading(false));
  }, [clearSession, refreshMe]);

  const login = useCallback(async (email: string, password: string) => {
    const t = await api<TokenResponse>('/auth/login', {
      method: 'POST',
      body: { email, password },
      anonymous: true,
    });
    saveTokens({ accessToken: t.accessToken, refreshToken: t.refreshToken });
    setMe(await api<Me>('/me'));
  }, []);

  const value = useMemo<AuthState>(
    () => ({
      me,
      loading,
      login,
      logout,
      logoutAll,
      refreshMe,
      isStaff: !!me?.staff,
      can: (...permissions) => !!me && permissions.some((p) => (me.permissions ?? []).includes(p)),
    }),
    [me, loading, login, logout, logoutAll, refreshMe],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used inside AuthProvider');
  return ctx;
}
