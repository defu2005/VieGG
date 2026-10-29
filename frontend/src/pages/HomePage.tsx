import { useEffect, useState } from 'react';
import { Link, Navigate } from 'react-router-dom';
import { api } from '../api/client';
import type { GameView, Listing, PageResponse } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { ListingCard, ListingSkeletons } from '../components/ListingCard';
import { errorMessage } from '../lib/format';
import { STAFF_LINKS } from '../lib/staff';
import { useSiteSettings } from '../lib/siteSettings';

const TRUST = [
  { icon: '🔒', title: 'Mật khẩu được mã hóa', text: 'Thông tin đăng nhập chỉ hiện cho bạn, trong đúng khung giờ thuê.' },
  { icon: '💰', title: 'Cọc riêng từng đơn', text: 'Tiền cọc được giữ riêng và hoàn lại sau khi kết thúc nếu không phát sinh vấn đề.' },
  { icon: '🔁', title: 'Đổi mật khẩu sau mỗi lượt', text: 'Chủ account phải đổi mật khẩu khi hết giờ để bảo vệ account.' },
  { icon: '🛟', title: 'Khiếu nại có bằng chứng', text: 'Không đăng nhập được hoặc sai mô tả? Mở khiếu nại ngay trong đơn.' },
];

const STEPS = [
  { n: 1, title: 'Chọn account', text: 'Lọc theo game, giá và khung giờ còn trống.' },
  { n: 2, title: 'Đặt giờ và thanh toán', text: 'Chọn thời lượng, thanh toán tiền thuê cùng tiền cọc.' },
  { n: 3, title: 'Nhận account và chơi', text: 'Xem thông tin đăng nhập khi đến giờ, chơi và trả lại.' },
];

/** Customers get the marketplace home; staff accounts go straight to their first back-office page. */
export function HomePage() {
  const { isStaff, can } = useAuth();
  if (!isStaff) return <CustomerHome />;
  const first = STAFF_LINKS.find((l) => can(...l.anyOf));
  if (first) return <Navigate to={first.to} replace />;
  return <p className="muted">Tài khoản nhân viên này chưa được cấp quyền quản trị nào. Liên hệ quản trị viên để được phân quyền.</p>;
}

function CustomerHome() {
  const { me } = useAuth();
  const site = useSiteSettings();
  const [games, setGames] = useState<GameView[]>([]);
  const [listings, setListings] = useState<Listing[] | null>(null);
  const [gamesError, setGamesError] = useState<string | null>(null);
  const [listingsError, setListingsError] = useState<string | null>(null);

  useEffect(() => {
    api<GameView[]>('/games')
      .then(setGames)
      .catch((err) => setGamesError(errorMessage(err)));
    api<PageResponse<Listing>>('/listings/search?size=6')
      .then((p) => setListings(p.content))
      .catch((err) => setListingsError(errorMessage(err)));
  }, []);

  const gameName = (id: string) => games.find((g) => g.id === id)?.name;

  return (
    <div className="home">
      <section className="hero">
        {site.get('home.hero_eyebrow').trim() && <span className="hero-eyebrow">{site.get('home.hero_eyebrow')}</span>}
        <h1>{site.get('home.hero_title')}</h1>
        {site.get('home.hero_subtitle').trim() && <p style={{ whiteSpace: 'pre-line' }}>{site.get('home.hero_subtitle')}</p>}
        <div className="row" style={{ marginTop: '1.25rem' }}>
          <Link to="/listings" className="btn btn-lg btn-light">
            🔎 Tìm account ngay
          </Link>
          {!me && (
            <Link to="/register" className="btn btn-lg btn-outline-light">
              Đăng ký miễn phí
            </Link>
          )}
          <Link to="/help" className="btn btn-lg btn-outline-light">
            Cách hoạt động
          </Link>
        </div>
        <ul className="hero-points">
          <li>Mật khẩu mã hoá</li>
          <li>Cọc hoàn lại khi trả account</li>
          <li>Khiếu nại ngay trong đơn</li>
        </ul>
      </section>

      {gamesError && <p className="error">Không tải được danh sách game: {gamesError}</p>}

      {games.length > 0 && (
        <section>
          <div className="section-head">
            <div>
              <h2>Chọn theo game</h2>
              <p className="muted">Bấm vào game để xem các account đang cho thuê.</p>
            </div>
          </div>
          <div className="grid">
            {games.map((g) => (
              <Link key={g.id} to={`/listings?game=${g.id}`} className="game-tile">
                <span className="avatar" aria-hidden="true">
                  {g.name.slice(0, 1).toUpperCase()}
                </span>
                <span>
                  <strong>{g.name}</strong>
                  <span className="muted" style={{ display: 'block' }}>
                    {g.publisher ?? 'Xem account đang cho thuê'}
                  </span>
                </span>
                <span className="game-tile-arrow" aria-hidden="true">
                  →
                </span>
              </Link>
            ))}
          </div>
        </section>
      )}

      <section>
        <div className="section-head">
          <div>
            <h2>🔥 Account giá tốt</h2>
            <p className="muted">Mới đăng, sẵn sàng cho thuê.</p>
          </div>
          <Link to="/listings" className="section-link">
            Xem tất cả →
          </Link>
        </div>
        {listingsError ? (
          <p className="error">Không tải được danh sách account: {listingsError}</p>
        ) : listings === null ? (
          <ListingSkeletons count={3} />
        ) : listings.length === 0 ? (
          <div className="empty-state">
            <span className="empty-icon" aria-hidden="true">
              🕹️
            </span>
            <strong>Chưa có account nào đang cho thuê</strong>
            <p className="muted">Quay lại sau, hoặc trở thành người đầu tiên đăng account.</p>
          </div>
        ) : (
          <div className="grid">
            {listings.map((l) => (
              <ListingCard key={l.id} listing={l} gameName={gameName(l.gameId)} />
            ))}
          </div>
        )}
      </section>

      <section className="card">
        <h2>Thuê chỉ với 3 bước</h2>
        <div className="trust">
          {STEPS.map((s) => (
            <div key={s.n}>
              <span className="avatar" aria-hidden="true">
                {s.n}
              </span>
              <span>
                <strong>{s.title}</strong>
                <span className="muted">{s.text}</span>
              </span>
            </div>
          ))}
        </div>
      </section>

      <section className="card">
        <h2>Vì sao thuê trên VieGG</h2>
        <div className="trust">
          {TRUST.map((t) => (
            <div key={t.title}>
              <span className="icon" aria-hidden="true">
                {t.icon}
              </span>
              <span>
                <strong>{t.title}</strong>
                <span className="muted">{t.text}</span>
              </span>
            </div>
          ))}
        </div>
      </section>

      <section className="cta-band row spread">
        <div>
          <h2>💸 Có account game nhàn rỗi?</h2>
          <p className="muted" style={{ margin: 0 }}>Cho thuê theo giờ để kiếm thêm thu nhập. Bạn tự đặt giá và cách nhận đơn.</p>
        </div>
        <Link to={me ? '/my-listings' : '/register'} className="btn btn-primary btn-lg">
          Đăng account cho thuê
        </Link>
      </section>
    </div>
  );
}
