import { useEffect, useRef, useState, type ReactNode } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';

const DONE_KEY = 'rentgg.onboarded.';
const OPEN_EVENT = 'rentgg:open-tour';

/** Opens the welcome tour again from anywhere (help page, menu). */
export function openTour() {
  window.dispatchEvent(new Event(OPEN_EVENT));
}

function isDone(userId: string): boolean {
  try {
    return localStorage.getItem(DONE_KEY + userId) === '1';
  } catch {
    return true; // no storage: don't nag on every page load
  }
}

function markDone(userId: string) {
  try {
    localStorage.setItem(DONE_KEY + userId, '1');
  } catch {
    /* ignore */
  }
}

interface Step {
  icon: string;
  title: string;
  body: ReactNode;
}

function steps(name: string, isStaff: boolean): Step[] {
  if (isStaff) {
    return [
      {
        icon: '👋',
        title: `Chào mừng ${name}`,
        body: (
          <>
            <p>Đây là tài khoản nhân viên VieGG, chỉ dùng để vận hành hệ thống. Tài khoản này không thuê, không cho thuê account và không có ví.</p>
            <p className="muted">Muốn thuê hoặc cho thuê như khách hàng, hãy dùng một tài khoản cá nhân riêng.</p>
          </>
        ),
      },
      {
        icon: '🛠️',
        title: 'Menu quản trị',
        body: (
          <p>
            Thanh menu chỉ hiện những mục bạn được phân quyền: kiểm duyệt bài đăng, khiếu nại, game, người dùng, phê duyệt và tài chính. Đầu mỗi trang đều có phần giải thích ngắn.
          </p>
        ),
      },
    ];
  }
  const list: Step[] = [
    {
      icon: '👋',
      title: `Chào mừng ${name} đến với VieGG`,
      body: (
        <>
          <p>VieGG là nơi thuê account game theo giờ, hoặc cho thuê account của bạn để kiếm thêm thu nhập.</p>
          <p className="muted">Hướng dẫn nhanh này mất khoảng 1 phút. Bạn có thể xem lại bất cứ lúc nào ở mục “Hướng dẫn” trên thanh menu.</p>
        </>
      ),
    },
    {
      icon: '🎮',
      title: 'Thuê account',
      body: (
        <ul>
          <li>
            Vào <strong>Tìm account</strong>, lọc theo game và giá.
          </li>
          <li>Chọn giờ bắt đầu, thời lượng rồi thanh toán <strong>tiền thuê + tiền cọc</strong>.</li>
          <li>Một số chủ account cần duyệt đơn trước. Nếu họ từ chối, bạn được hoàn đủ tiền.</li>
          <li>
            Đến giờ, mở đơn trong <strong>Đơn của tôi</strong> để xem tài khoản và mật khẩu.
          </li>
          <li>Đăng nhập được thì bấm “Xác nhận đã nhận account”.</li>
        </ul>
      ),
    },
    {
      icon: '💼',
      title: 'Cho thuê account của bạn',
      body: (
        <ul>
          <li>
            Vào <strong>Account cho thuê</strong>, thêm account game. Mật khẩu được mã hóa, chỉ người thuê thấy trong giờ thuê.
          </li>
          <li>Tạo bài đăng (listing): đặt tiêu đề, giá theo giờ, tiền cọc.</li>
          <li>Gửi duyệt. Khi được duyệt, người thuê sẽ tìm thấy account của bạn.</li>
          <li>Sau mỗi lượt thuê, đổi mật khẩu để giữ an toàn cho account.</li>
        </ul>
      ),
    },
    {
      icon: '💰',
      title: 'Ví và tiền cọc',
      body: (
        <ul>
          <li>Mọi giao dịch đi qua ví: nạp tiền vào ví, đặt đơn sẽ trừ tiền thuê và cọc từ ví.</li>
          <li>Tiền cọc được VieGG giữ riêng cho từng đơn và hoàn về ví khi thuê xong mà không có sự cố.</li>
          <li>Doanh thu cho thuê được giữ một thời gian ngắn để xử lý khiếu nại, sau đó cộng vào ví.</li>
          <li>
            Vào <strong>Ví</strong> để nạp tiền, xem lịch sử giao dịch và rút tiền về ngân hàng.
          </li>
        </ul>
      ),
    },
    {
      icon: '🛟',
      title: 'Khi gặp sự cố',
      body: (
        <ul>
          <li>Không đăng nhập được hoặc account sai mô tả? Mở đơn và bấm “Gửi khiếu nại”, kèm link ảnh hoặc video.</li>
          <li>Mọi thay đổi về đơn đều có trong mục Thông báo.</li>
          <li>Rê chuột lên các nhãn trạng thái để xem giải thích.</li>
        </ul>
      ),
    },
  ];
  return list;
}

/** Welcome tour shown once per user after their first sign-in, and on demand via openTour(). */
export function Onboarding() {
  const { me } = useAuth();
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const [i, setI] = useState(0);
  const primaryRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (me && !isDone(me.id)) {
      setI(0);
      setOpen(true);
    }
  }, [me]);

  useEffect(() => {
    const onOpen = () => {
      setI(0);
      setOpen(true);
    };
    window.addEventListener(OPEN_EVENT, onOpen);
    return () => window.removeEventListener(OPEN_EVENT, onOpen);
  }, []);

  useEffect(() => {
    if (open) primaryRef.current?.focus();
  }, [open, i]);

  if (!open || !me) return null;

  const isStaff = !!me.staff;
  const all = steps(me.displayName, isStaff);
  const step = all[i];
  const last = i === all.length - 1;

  const close = (to?: string) => {
    markDone(me.id);
    setOpen(false);
    if (to) navigate(to);
  };

  return (
    <div className="overlay" onKeyDown={(e) => e.key === 'Escape' && close()}>
      <div className="dialog" role="dialog" aria-modal="true" aria-labelledby="tour-title">
        <div className="tour-icon" aria-hidden="true">
          {step.icon}
        </div>
        <h2 id="tour-title">{step.title}</h2>
        {step.body}
        <div className="row spread" style={{ marginTop: '1rem' }}>
          <div className="dots" aria-label={`Bước ${i + 1} trên ${all.length}`}>
            {all.map((_, k) => (
              <span key={k} className={k === i ? 'on' : ''} />
            ))}
          </div>
          <div className="row">
            {!last && (
              <button type="button" className="btn btn-ghost" onClick={() => close()}>
                Bỏ qua
              </button>
            )}
            {i > 0 && (
              <button type="button" className="btn" onClick={() => setI(i - 1)}>
                Quay lại
              </button>
            )}
            {!last ? (
              <button ref={primaryRef} type="button" className="btn btn-primary" onClick={() => setI(i + 1)}>
                Tiếp
              </button>
            ) : (
              <button ref={primaryRef} type="button" className="btn btn-primary" onClick={() => close()}>
                Bắt đầu sử dụng
              </button>
            )}
          </div>
        </div>
        {last && (
          <div className="row" style={{ marginTop: '0.75rem' }}>
            <button type="button" className="link-btn" onClick={() => close('/listings')}>
              Tìm account để thuê →
            </button>
            <button type="button" className="link-btn" onClick={() => close('/my-listings')}>
              Đăng account cho thuê →
            </button>
          </div>
        )}
      </div>
    </div>
  );
}
