import { useEffect } from 'react';
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { useScrollToHash, useUnreadCount } from '../lib/unreadNotifications';
import { STAFF_LINKS } from '../lib/staff';
import { useHelpdeskUnread } from '../lib/helpdesk';
import { Onboarding } from './Onboarding';
import { DevTimePanel } from './DevTimePanel';
import { SiteBanner } from './SiteBanner';
import { useSiteSettings } from '../lib/siteSettings';

export function Layout() {
  const { me, logout, can, isStaff } = useAuth();
  const navigate = useNavigate();
  const site = useSiteSettings();
  const siteTitle = site.get('site.title');
  const supportEmail = site.get('support.email').trim();
  const supportHotline = site.get('support.hotline').trim();

  useEffect(() => {
    document.title = siteTitle;
  }, [siteTitle]);

  const handleLogout = () => {
    logout();
    navigate('/login');
  };

  const staffLinks = STAFF_LINKS.filter((l) => can(...l.anyOf));
  const location = useLocation();
  const unread = useUnreadCount(!!me, location.pathname);
  const helpdesk = useHelpdeskUnread(!!me, location.pathname);
  const helpdeskCount = helpdesk.unreadTickets + helpdesk.awaitingStaff;
  const helpdeskBadge =
    helpdeskCount > 0 ? (
      <span className="nav-badge" aria-label={`${helpdeskCount} cần xem`}>
        {helpdeskCount > 99 ? '99+' : helpdeskCount}
      </span>
    ) : null;
  const onTickets = location.pathname.startsWith('/tickets');
  useScrollToHash(location.pathname, location.hash);

  return (
    <div className="app">
      <SiteBanner />
      <header className="topbar">
        <NavLink to="/" className="brand" aria-label="VieGG — Trang chủ">
          <span className="brand-mark" aria-hidden="true">
            GG
          </span>
          <span className="brand-text">
            Vie<b>GG</b>
          </span>
          {isStaff && <span className="badge staff-badge">Quản trị</span>}
        </NavLink>
        <nav className="nav">
          {/* Staff accounts get the back office only; customer features stay with customer accounts. */}
          {isStaff
            ? staffLinks.map((l) => (
                <NavLink key={l.to} to={l.to}>
                  {l.label}
                  {l.to === '/tickets' && helpdeskBadge}
                </NavLink>
              ))
            : (
              <>
                <NavLink to="/listings">Tìm account</NavLink>
                {me && <NavLink to="/orders">Đơn của tôi</NavLink>}
                {me && <NavLink to="/my-listings">Account cho thuê</NavLink>}
                {me && <NavLink to="/wallet">Ví</NavLink>}
                {me && (
                  <NavLink to="/tickets">
                    Hỗ trợ
                    {helpdeskBadge}
                  </NavLink>
                )}
              </>
            )}
          {me && (
            <NavLink to="/notifications">
              Thông báo
              {unread > 0 && (
                <span className="nav-badge" aria-label={`${unread} chưa đọc`}>
                  {unread > 99 ? '99+' : unread}
                </span>
              )}
            </NavLink>
          )}
          <NavLink to="/help">Hướng dẫn</NavLink>
        </nav>
        <div className="account">
          {me ? (
            <>
              <NavLink to="/profile" className="muted account-name" title="Hồ sơ của tôi">
                <span className="account-avatar" aria-hidden="true">
                  {me.displayName.trim().slice(0, 1).toUpperCase() || '?'}
                </span>
                <span className="account-label">{me.displayName}</span>
              </NavLink>
              <button className="btn btn-ghost" onClick={handleLogout}>
                Đăng xuất
              </button>
            </>
          ) : (
            <>
              <NavLink to="/login">Đăng nhập</NavLink>
              <NavLink to="/register" className="btn btn-primary">
                Đăng ký
              </NavLink>
            </>
          )}
        </div>
        {!isStaff && staffLinks.length > 0 && (
          <nav className="nav staff-nav" aria-label="Quản trị">
            <span className="muted">Quản trị:</span>
            {staffLinks.map((l) => (
              <NavLink key={l.to} to={l.to}>
                {l.label}
              </NavLink>
            ))}
          </nav>
        )}
      </header>
      <main className="container">
        <Outlet />
      </main>
      <footer className="footer">
        <div className="footer-inner">
          <span>
            <strong style={{ color: 'var(--text)' }}>{site.get('site.name')}</strong>
            {site.get('site.footer_text').trim() && <> · {site.get('site.footer_text')}</>}
          </span>
          <nav className="footer-links" aria-label="Liên kết cuối trang">
            {supportEmail && <a href={`mailto:${supportEmail}`}>✉️ {supportEmail}</a>}
            {supportHotline && <a href={`tel:${supportHotline.replace(/[^0-9+]/g, '')}`}>📞 {supportHotline}</a>}
            {!isStaff && <NavLink to="/listings">Tìm account</NavLink>}
            <NavLink to="/help">Hướng dẫn</NavLink>
            <NavLink to="/help#hoi-dap">Câu hỏi thường gặp</NavLink>
          </nav>
        </div>
      </footer>
      {me && !isStaff && !onTickets && (
        <button className="btn btn-primary helpdesk-fab" onClick={() => navigate('/tickets?new=1')} title="Hỏi đáp, nhờ hỗ trợ, khiếu nại">
          💬 Chat với hỗ trợ
          {helpdeskBadge}
        </button>
      )}
      <Onboarding />
      {import.meta.env.DEV && <DevTimePanel />}
    </div>
  );
}
