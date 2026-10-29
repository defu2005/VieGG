import { useState } from 'react';
import { Link } from 'react-router-dom';
import { isInternalLink, useSiteSettings } from '../lib/siteSettings';

const DISMISS_KEY = 'rentgg.banner.dismissed';
const ICONS: Record<string, string> = { info: 'ℹ️', success: '✅', warning: '⚠️', danger: '⛔' };

export interface BannerContent {
  message: string;
  variant: string;
  linkUrl: string;
  linkLabel: string;
  dismissible: boolean;
}

/** Announcement banner at the top of every page (admin setting "banner.*"). */
export function SiteBanner() {
  const s = useSiteSettings();
  const content: BannerContent = {
    message: s.get('banner.message').trim(),
    variant: s.get('banner.variant'),
    linkUrl: s.get('banner.link_url'),
    linkLabel: s.get('banner.link_label'),
    dismissible: s.bool('banner.dismissible'),
  };
  // Dismissal is remembered per message: a new message shows again.
  const signature = `${content.variant}|${content.message}|${content.linkUrl}`;
  const [dismissed, setDismissed] = useState<string | null>(() => {
    try {
      return localStorage.getItem(DISMISS_KEY);
    } catch {
      return null;
    }
  });

  if (!s.bool('banner.enabled') || !content.message) return null;
  if (content.dismissible && dismissed === signature) return null;

  const dismiss = () => {
    setDismissed(signature);
    try {
      localStorage.setItem(DISMISS_KEY, signature);
    } catch {
      // Storage blocked: the banner hides for this page view only.
    }
  };

  return <SiteBannerView content={content} onDismiss={content.dismissible ? dismiss : undefined} />;
}

export function SiteBannerView({ content, onDismiss }: { content: BannerContent; onDismiss?: () => void }) {
  const variant = ICONS[content.variant] ? content.variant : 'info';
  const label = content.linkLabel.trim() || 'Xem chi tiết';
  return (
    <div className={`site-banner site-banner-${variant}`} role={variant === 'danger' || variant === 'warning' ? 'alert' : 'status'}>
      <div className="site-banner-inner">
        <span aria-hidden="true">{ICONS[variant]}</span>
        <p className="site-banner-text">
          {content.message}
          {content.linkUrl &&
            (isInternalLink(content.linkUrl) ? (
              <>
                {' '}
                <Link to={content.linkUrl}>{label} →</Link>
              </>
            ) : (
              <>
                {' '}
                <a href={content.linkUrl} target="_blank" rel="noopener noreferrer">
                  {label} ↗
                </a>
              </>
            ))}
        </p>
        {onDismiss && (
          <button type="button" className="site-banner-close" onClick={onDismiss} aria-label="Ẩn thông báo">
            ✕
          </button>
        )}
      </div>
    </div>
  );
}
