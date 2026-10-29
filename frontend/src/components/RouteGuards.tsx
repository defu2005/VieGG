import type { ReactNode } from 'react';
import { Link, Navigate, useLocation } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';

/** Redirects anonymous visitors to /login and remembers where they were going. */
export function RequireAuth({ children }: { children: ReactNode }) {
  const { me, loading } = useAuth();
  const location = useLocation();
  if (loading) return <p className="muted">Đang tải…</p>;
  if (!me) return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  return <>{children}</>;
}

/** Shows the page only to users holding at least one of the given permissions. */
export function RequirePermission({ anyOf, children }: { anyOf: string[]; children: ReactNode }) {
  const { can } = useAuth();
  if (!can(...anyOf)) return <p className="error">Bạn không có quyền truy cập trang này.</p>;
  return <>{children}</>;
}

/** Customer-only pages (renting, listing, wallet): staff accounts are sent back to the back office. */
export function RequireCustomer({ children }: { children: ReactNode }) {
  const { isStaff } = useAuth();
  if (isStaff) {
    return (
      <section className="card stack">
        <h1>Trang dành cho khách hàng</h1>
        <p className="muted">Tài khoản nhân viên chỉ dùng để vận hành hệ thống, không thuê, không cho thuê account và không có ví. Muốn dùng các tính năng này, hãy đăng nhập bằng tài khoản cá nhân riêng.</p>
        <p>
          <Link to="/" className="btn btn-primary">
            Về trang quản trị
          </Link>
        </p>
      </section>
    );
  }
  return <>{children}</>;
}
