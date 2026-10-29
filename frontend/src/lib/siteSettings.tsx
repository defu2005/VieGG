import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { api } from '../api/client';

/**
 * Public platform settings managed by admins (GET /settings/public): banner, site texts, withdrawal limits.
 * Defaults mirror the backend catalog (com.rentgg.settings.domain.Setting) so the page renders before the
 * request returns or when it fails.
 */
export const SETTING_DEFAULTS = {
  'withdrawal.enabled': 'true',
  'withdrawal.min_amount': '10000',
  'withdrawal.max_amount': '0',
  'withdrawal.notice': 'Yêu cầu rút tiền được xử lý trong 1–2 ngày làm việc.',
  'site.name': 'VieGG',
  'site.title': 'VieGG — Thuê account game theo giờ',
  'site.footer_text': 'Thuê account game theo giờ, an toàn và minh bạch.',
  'support.email': '',
  'support.hotline': '',
  'home.hero_eyebrow': '🎮 Chợ thuê account game theo giờ',
  'home.hero_title': 'Thuê account game theo giờ — nhận ngay, chơi an toàn',
  'home.hero_subtitle':
    'Kết nối người cần chơi với chủ account đã được kiểm duyệt. Tiền thuê và cọc được giữ theo quy định, hoàn tất trong vài phút.',
  'banner.enabled': 'false',
  'banner.message': '',
  'banner.variant': 'info',
  'banner.link_url': '',
  'banner.link_label': 'Xem chi tiết',
  'banner.dismissible': 'true',
};

export type SettingKey = keyof typeof SETTING_DEFAULTS;

interface SiteSettingsValue {
  get: (key: SettingKey) => string;
  bool: (key: SettingKey) => boolean;
  num: (key: SettingKey) => number;
  reload: () => Promise<void>;
}

const Ctx = createContext<SiteSettingsValue | null>(null);

export function SiteSettingsProvider({ children }: { children: ReactNode }) {
  const [values, setValues] = useState<Record<string, string>>(SETTING_DEFAULTS);

  const reload = useCallback(async () => {
    try {
      const v = await api<Record<string, string>>('/settings/public', { anonymous: true });
      setValues({ ...SETTING_DEFAULTS, ...v });
    } catch {
      // Keep the last known values (defaults on first load).
    }
  }, []);

  useEffect(() => {
    reload();
    // Pick up admin changes when the user comes back to the tab.
    const onVisible = () => {
      if (document.visibilityState === 'visible') reload();
    };
    document.addEventListener('visibilitychange', onVisible);
    return () => document.removeEventListener('visibilitychange', onVisible);
  }, [reload]);

  const value = useMemo<SiteSettingsValue>(
    () => ({
      get: (k) => values[k] ?? SETTING_DEFAULTS[k],
      bool: (k) => (values[k] ?? SETTING_DEFAULTS[k]) === 'true',
      num: (k) => Number(values[k] ?? SETTING_DEFAULTS[k]) || 0,
      reload,
    }),
    [values, reload],
  );

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useSiteSettings(): SiteSettingsValue {
  const v = useContext(Ctx);
  if (!v) throw new Error('useSiteSettings outside SiteSettingsProvider');
  return v;
}

/** In-app paths go through the router; anything else must be http(s) (the backend enforces the same rule). */
export function isInternalLink(url: string): boolean {
  return url.startsWith('/') && !url.startsWith('//');
}
