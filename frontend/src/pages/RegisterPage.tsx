import { useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { api } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import { errorMessage } from '../lib/format';

export function RegisterPage() {
  const { login } = useAuth();
  const navigate = useNavigate();

  const [displayName, setDisplayName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [dateOfBirth, setDateOfBirth] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      await api('/auth/register', {
        method: 'POST',
        body: { email, password, displayName, dateOfBirth },
        anonymous: true,
      });
      await login(email, password);
      navigate('/', { replace: true });
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="card" style={{ maxWidth: 480, margin: '0 auto' }}>
      <h1>Đăng ký</h1>
      <form className="form" onSubmit={onSubmit}>
        <div className="field">
          <label htmlFor="displayName">Tên hiển thị</label>
          <input id="displayName" required maxLength={100} value={displayName} onChange={(e) => setDisplayName(e.target.value)} />
        </div>
        <div className="field">
          <label htmlFor="email">Email</label>
          <input id="email" type="email" autoComplete="email" required value={email} onChange={(e) => setEmail(e.target.value)} />
        </div>
        <div className="field">
          <label htmlFor="password">Mật khẩu</label>
          <input
            id="password"
            type="password"
            autoComplete="new-password"
            required
            minLength={10}
            maxLength={200}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
          <span className="field-hint">Tối thiểu 10 ký tự.</span>
        </div>
        <div className="field">
          <label htmlFor="dob">Ngày sinh</label>
          <input id="dob" type="date" required value={dateOfBirth} onChange={(e) => setDateOfBirth(e.target.value)} />
          <span className="field-hint">Bạn cần đủ 18 tuổi để sử dụng VieGG.</span>
        </div>
        {error && <p className="error">{error}</p>}
        <button className="btn btn-primary" disabled={submitting}>
          {submitting ? 'Đang tạo tài khoản…' : 'Đăng ký'}
        </button>
        <p className="muted">
          Đã có tài khoản? <Link to="/login">Đăng nhập</Link>
        </p>
      </form>
    </div>
  );
}
