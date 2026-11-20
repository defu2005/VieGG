import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { api } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import { errorMessage } from '../lib/format';

interface Enrolment {
  secret: string;
  otpauthUri: string;
}

/**
 * Shown in place of back-office pages to a staff account whose session has not passed the second factor.
 * Not enrolled: set up an authenticator (secret shown as text, no QR). Enrolled: sign in again with a code.
 */
export function StaffMfaPanel() {
  const { me, logout } = useAuth();
  const navigate = useNavigate();
  const [enrolment, setEnrolment] = useState<Enrolment | null>(null);
  const [code, setCode] = useState('');
  const [confirmed, setConfirmed] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const leave = () => {
    logout();
    navigate('/login', { replace: true });
  };

  const start = async () => {
    setError(null);
    setBusy(true);
    try {
      setEnrolment(await api<Enrolment>('/me/mfa/totp/start', { method: 'POST' }));
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  };

  const confirm = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    setBusy(true);
    try {
      await api('/me/mfa/totp/confirm', { method: 'POST', body: { code: code.trim() } });
      setConfirmed(true);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  };

  if (!me) return null;

  if (me.mfa.enrolled) {
    return (
      <section className="card stack">
        <h1>Cần mã xác thực hai bước</h1>
        <p className="muted">Phiên này chưa xác thực hai bước, nên tài khoản nhân viên chưa dùng được trang quản trị. Đăng xuất, đăng nhập lại và nhập mã 6 số trong ứng dụng xác thực.</p>
        <p>
          <button className="btn btn-primary" onClick={leave}>Đăng xuất</button>
        </p>
      </section>
    );
  }

  if (confirmed) {
    return (
      <section className="card stack">
        <h1>Đã bật xác thực hai bước</h1>
        <p className="muted">Đăng xuất và đăng nhập lại. Mã vừa dùng để xác nhận không dùng lại được: hãy đợi mã kế tiếp (sau 30 giây) trong ứng dụng xác thực.</p>
        <p>
          <button className="btn btn-primary" onClick={leave}>Đăng xuất</button>
        </p>
      </section>
    );
  }

  return (
    <section className="card stack">
      <h1>Bật xác thực hai bước</h1>
      <p className="muted">Tài khoản nhân viên cần mã xác thực hai bước trước khi dùng trang quản trị. Dùng ứng dụng như Google Authenticator hoặc Authy.</p>
      {!enrolment ? (
        <p>
          <button className="btn btn-primary" onClick={start} disabled={busy}>
            {busy ? 'Đang tạo…' : 'Tạo khoá bí mật'}
          </button>
        </p>
      ) : (
        <form className="form" onSubmit={confirm}>
          <p>Nhập khoá này vào ứng dụng xác thực (hoặc dán liên kết bên dưới):</p>
          <p>
            <code style={{ wordBreak: 'break-all' }}>{enrolment.secret}</code>
          </p>
          <p>
            <code style={{ wordBreak: 'break-all' }}>{enrolment.otpauthUri}</code>
          </p>
          <div className="field">
            <label htmlFor="mfaCode">Mã 6 số từ ứng dụng</label>
            <input
              id="mfaCode"
              inputMode="numeric"
              autoComplete="one-time-code"
              required
              maxLength={6}
              value={code}
              onChange={(e) => setCode(e.target.value)}
            />
          </div>
          <button className="btn btn-primary" disabled={busy}>
            {busy ? 'Đang xác nhận…' : 'Xác nhận'}
          </button>
        </form>
      )}
      {error && <p className="error">{error}</p>}
    </section>
  );
}
