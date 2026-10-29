import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { api, saveTokens } from '../api/client';
import type { Me, TokenResponse } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { StatusBadge } from '../components/StatusBadge';
import { KYC_STATUS, labelOf, ROLE, USER_STATUS } from '../lib/labels';
import { FlashMessages, useFlash } from '../lib/useFlash';
import { CopyId } from '../components/CopyId';

export function ProfilePage() {
  const { me, refreshMe, isStaff, logoutAll } = useAuth();
  const navigate = useNavigate();
  const flash = useFlash();
  const nameFlash = useFlash();
  const passwordFlash = useFlash();
  const sessionFlash = useFlash();
  const [editingName, setEditingName] = useState(false);
  const [displayName, setDisplayName] = useState('');
  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [fullName, setFullName] = useState('');
  const [idNumber, setIdNumber] = useState('');
  const [phone, setPhone] = useState('');

  if (!me) return null;

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault();
    const ok = await flash.run(
      () => api('/me/kyc', { method: 'POST', body: { fullName, idNumber, phone: phone || undefined } }),
      'Đã gửi thông tin xác minh, chờ quản trị viên duyệt.',
    );
    if (ok) {
      setIdNumber('');
      refreshMe();
    }
  };

  const startEditName = () => {
    setDisplayName(me.displayName);
    nameFlash.setError(null);
    nameFlash.setNotice(null);
    setEditingName(true);
  };

  const onSaveName = async (e: FormEvent) => {
    e.preventDefault();
    const ok = await nameFlash.run(
      () => api<Me>('/me', { method: 'PUT', body: { displayName: displayName.trim() } }),
      'Đã cập nhật tên hiển thị.',
    );
    if (ok) {
      setEditingName(false);
      refreshMe();
    }
  };

  const onChangePassword = async (e: FormEvent) => {
    e.preventDefault();
    if (newPassword !== confirmPassword) {
      passwordFlash.setNotice(null);
      passwordFlash.setError('Mật khẩu nhập lại không khớp.');
      return;
    }
    const ok = await passwordFlash.run(async () => {
      const t = await api<TokenResponse>('/me/password', {
        method: 'POST',
        body: { currentPassword, newPassword },
      });
      // Every old session was revoked; keep this device logged in with the new tokens.
      saveTokens({ accessToken: t.accessToken, refreshToken: t.refreshToken });
    }, 'Đã đổi mật khẩu. Các thiết bị khác sẽ phải đăng nhập lại.');
    if (ok) {
      setCurrentPassword('');
      setNewPassword('');
      setConfirmPassword('');
    }
  };

  const onLogoutAll = async () => {
    if (!window.confirm('Đăng xuất khỏi tất cả thiết bị, kể cả thiết bị này?')) return;
    const ok = await sessionFlash.run(() => logoutAll());
    if (ok) navigate('/login');
  };

  return (
    <div className="stack">
      <h1>Hồ sơ</h1>
      <section className="card">
        <dl className="kv">
          <dt>Tên hiển thị</dt>
          <dd>
            {editingName ? (
              <form className="row" onSubmit={onSaveName}>
                <input
                  aria-label="Tên hiển thị mới"
                  required
                  maxLength={100}
                  value={displayName}
                  onChange={(e) => setDisplayName(e.target.value)}
                  autoFocus
                />
                <button className="btn btn-primary btn-sm" disabled={nameFlash.busy || !displayName.trim()}>
                  Lưu
                </button>
                <button type="button" className="btn btn-ghost btn-sm" onClick={() => setEditingName(false)}>
                  Huỷ
                </button>
              </form>
            ) : (
              <>
                {me.displayName}{' '}
                <button type="button" className="btn btn-ghost btn-sm" onClick={startEditName}>
                  Sửa
                </button>
              </>
            )}
            <FlashMessages error={nameFlash.error} notice={nameFlash.notice} />
          </dd>
          <dt>Email</dt>
          <dd>{me.email}</dd>
          <dt>Mã người dùng</dt>
          <dd>
            <span className="secret">{me.id}</span> <CopyId id={me.id} label="copy" />
            <div className="muted" style={{ fontSize: '0.85rem' }}>Gửi mã này (hoặc email) cho bộ phận hỗ trợ khi cần tra cứu tài khoản.</div>
          </dd>
          <dt>Ngày sinh</dt>
          <dd>{me.dateOfBirth}</dd>
          <dt>Loại tài khoản</dt>
          <dd>{isStaff ? 'Nhân viên (chỉ dùng trang quản trị)' : 'Khách hàng'}</dd>
          <dt>Vai trò</dt>
          <dd>{me.roles.filter((r) => !isStaff || r !== 'USER').map((r) => labelOf(ROLE, r)).join(', ')}</dd>
          <dt>Tình trạng tài khoản</dt>
          <dd>
            <StatusBadge status={me.status} dict={USER_STATUS} />
          </dd>
          {!isStaff && (
            <>
              <dt>Xác minh danh tính</dt>
              <dd>
                <StatusBadge status={me.kycStatus} dict={KYC_STATUS} />
              </dd>
            </>
          )}
        </dl>
      </section>

      <section className="card">
        <h2>Đổi mật khẩu</h2>
        <form className="form" onSubmit={onChangePassword}>
          <input type="email" autoComplete="username" value={me.email} readOnly hidden />
          <div className="field">
            <label htmlFor="pw-current">Mật khẩu hiện tại</label>
            <input
              id="pw-current"
              type="password"
              required
              autoComplete="current-password"
              value={currentPassword}
              onChange={(e) => setCurrentPassword(e.target.value)}
            />
          </div>
          <div className="field">
            <label htmlFor="pw-new">Mật khẩu mới (tối thiểu 10 ký tự)</label>
            <input
              id="pw-new"
              type="password"
              required
              minLength={10}
              maxLength={200}
              autoComplete="new-password"
              value={newPassword}
              onChange={(e) => setNewPassword(e.target.value)}
            />
          </div>
          <div className="field">
            <label htmlFor="pw-confirm">Nhập lại mật khẩu mới</label>
            <input
              id="pw-confirm"
              type="password"
              required
              minLength={10}
              maxLength={200}
              autoComplete="new-password"
              value={confirmPassword}
              onChange={(e) => setConfirmPassword(e.target.value)}
            />
          </div>
          <FlashMessages error={passwordFlash.error} notice={passwordFlash.notice} />
          <div>
            <button className="btn btn-primary" disabled={passwordFlash.busy}>
              Đổi mật khẩu
            </button>
          </div>
        </form>
      </section>

      <section className="card">
        <h2>Phiên đăng nhập</h2>
        <p className="muted">
          Nếu bạn nghi ngờ tài khoản bị người khác dùng, hãy đăng xuất khỏi mọi thiết bị rồi đổi mật khẩu.
        </p>
        <FlashMessages error={sessionFlash.error} notice={sessionFlash.notice} />
        <div>
          <button type="button" className="btn btn-danger" onClick={onLogoutAll} disabled={sessionFlash.busy}>
            Đăng xuất khỏi tất cả thiết bị
          </button>
        </div>
      </section>

      {!isStaff && (me.kycStatus === 'NONE' || me.kycStatus === 'REJECTED') && (
        <section className="card">
          <h2>Xác minh danh tính (tùy chọn)</h2>
          <p className="muted">Hiện không bắt buộc để thuê hoặc cho thuê. Hệ thống chỉ lưu 4 số cuối của giấy tờ và không lưu ảnh.</p>
          <form className="form" onSubmit={onSubmit}>
            <div className="field">
              <label htmlFor="kyc-name">Họ và tên</label>
              <input id="kyc-name" required maxLength={120} value={fullName} onChange={(e) => setFullName(e.target.value)} />
            </div>
            <div className="field">
              <label htmlFor="kyc-id">Số giấy tờ (CCCD/hộ chiếu)</label>
              <input id="kyc-id" required minLength={6} maxLength={20} value={idNumber} onChange={(e) => setIdNumber(e.target.value)} autoComplete="off" />
            </div>
            <div className="field">
              <label htmlFor="kyc-phone">Số điện thoại</label>
              <input id="kyc-phone" maxLength={20} value={phone} onChange={(e) => setPhone(e.target.value)} />
            </div>
            <FlashMessages error={flash.error} notice={flash.notice} />
            <div>
              <button className="btn btn-primary" disabled={flash.busy}>
                Gửi xác minh
              </button>
            </div>
          </form>
        </section>
      )}
    </div>
  );
}
