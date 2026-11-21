import { useState, type FormEvent } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { ApiError } from '../api/client';
import { errorMessage } from '../lib/format';

export function LoginPage() {
  const { login } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const from = (location.state as { from?: string } | null)?.from ?? '/';

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [totpCode, setTotpCode] = useState('');
  /** Staff with an authenticator: the server asked for a code (MFA_REQUIRED). Email and password are kept. */
  const [needsCode, setNeedsCode] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      await login(email, password, needsCode ? totpCode.trim() : undefined);
      navigate(from, { replace: true });
    } catch (err) {
      if (err instanceof ApiError && err.code === 'MFA_REQUIRED') {
        setNeedsCode(true);
      }
      if (err instanceof ApiError && err.code === 'MFA_INVALID') {
        setTotpCode('');
      }
      setError(errorMessage(err));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="card" style={{ maxWidth: 480, margin: '0 auto' }}>
      <h1>Đăng nhập</h1>
      <form className="form" onSubmit={onSubmit}>
        <div className="field">
          <label htmlFor="email">Email</label>
          <input id="email" type="email" autoComplete="email" required value={email} onChange={(e) => setEmail(e.target.value)} />
        </div>
        <div className="field">
          <label htmlFor="password">Mật khẩu</label>
          <input
            id="password"
            type="password"
            autoComplete="current-password"
            required
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
        </div>
        {needsCode && (
          <div className="field">
            <label htmlFor="totpCode">Mã xác thực hai bước (6 số)</label>
            <input
              id="totpCode"
              inputMode="numeric"
              autoComplete="one-time-code"
              required
              maxLength={6}
              value={totpCode}
              onChange={(e) => setTotpCode(e.target.value)}
            />
          </div>
        )}
        {error && <p className="error">{error}</p>}
        <button className="btn btn-primary" disabled={submitting}>
          {submitting ? 'Đang đăng nhập…' : 'Đăng nhập'}
        </button>
        <p className="muted">
          Chưa có tài khoản? <Link to="/register">Đăng ký</Link>
        </p>
      </form>
    </div>
  );
}
