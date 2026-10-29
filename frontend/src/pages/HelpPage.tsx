import { Link } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { openTour } from '../components/Onboarding';
import { StatusBadge } from '../components/StatusBadge';
import { DEPOSIT_STATUS, END_MODE, GAME_RISK, HANDOVER_MODE, LISTING_STATUS, ORDER_STATUS, SECURITY_STATE, WITHDRAWAL_STATUS, type LabelDict } from '../lib/labels';

const GLOSSARY: { title: string; dict: LabelDict; badge: boolean }[] = [
  { title: 'Trạng thái đơn thuê', dict: ORDER_STATUS, badge: true },
  { title: 'Trạng thái tiền cọc', dict: DEPOSIT_STATUS, badge: true },
  { title: 'Trạng thái bài đăng (listing)', dict: LISTING_STATUS, badge: true },
  { title: 'Tình trạng account game', dict: SECURITY_STATE, badge: true },
  { title: 'Trạng thái rút tiền', dict: WITHDRAWAL_STATUS, badge: true },
  { title: 'Mức rủi ro của game', dict: GAME_RISK, badge: true },
  { title: 'Cách kết thúc lượt thuê', dict: END_MODE, badge: false },
  { title: 'Cách bàn giao account', dict: HANDOVER_MODE, badge: false },
];

const FAQ: { q: string; a: string }[] = [
  { q: 'Tiền cọc dùng để làm gì?', a: 'Cọc bảo đảm người thuê dùng account đúng quy định. VieGG giữ riêng khoản này và hoàn lại sau khi thuê xong nếu chủ account không báo thiệt hại.' },
  { q: 'Bao lâu thì nhận lại tiền cọc?', a: 'Sau khi lượt thuê kết thúc, cọc được giữ thêm một khoảng ngắn (tùy game, thường 72 giờ) để chủ account kiểm tra, sau đó tự động hoàn.' },
  { q: 'Tôi có thể hủy đơn không?', a: 'Có. Hủy sớm trước giờ bắt đầu thì được hoàn đủ. Hủy sát giờ chỉ được hoàn một phần theo chính sách của game, hiển thị ở trang chi tiết account.' },
  { q: 'Không đăng nhập được account thì làm sao?', a: 'Mở đơn, kéo xuống phần Khiếu nại, chọn “Không đăng nhập được” và gửi kèm ảnh chụp màn hình. Hãy làm ngay trong khung giờ khiếu nại sau khi xem thông tin đăng nhập.' },
  { q: 'Mật khẩu account của tôi có an toàn không?', a: 'Mật khẩu được mã hóa khi lưu, chỉ hiện cho đúng người thuê trong đúng khung giờ, và mọi lần xem đều được ghi lại. Sau mỗi lượt thuê bạn nên đổi mật khẩu.' },
  { q: 'Khi nào tôi rút được doanh thu?', a: 'Doanh thu được giữ một thời gian sau khi lượt thuê kết thúc để xử lý khiếu nại, rồi chuyển sang “Có thể rút” trong Ví. Bạn gửi yêu cầu rút và bộ phận tài chính sẽ chuyển khoản.' },
  { q: '“Tự động nhận đơn” là gì?', a: 'Bật lựa chọn này thì đơn được xác nhận ngay khi người thuê thanh toán. Tắt đi thì bạn phải tự duyệt từng đơn trong thời hạn cho phép.' },
];

export function HelpPage() {
  const { me } = useAuth();
  const isStaff = !!me?.staff;

  return (
    <div className="stack">
      <div className="row spread">
        <h1>Hướng dẫn sử dụng</h1>
        {me && (
          <button className="btn" onClick={openTour}>
            Xem lại hướng dẫn nhanh
          </button>
        )}
      </div>

      <nav className="toc" aria-label="Mục lục">
        <a className="chip" href="#thue">Thuê account</a>
        <a className="chip" href="#cho-thue">Cho thuê account</a>
        <a className="chip" href="#vi">Ví và tiền cọc</a>
        <a className="chip" href="#khieu-nai">Khiếu nại</a>
        <a className="chip" href="#trang-thai">Giải thích trạng thái</a>
        <a className="chip" href="#hoi-dap">Hỏi đáp</a>
        {isStaff && <a className="chip" href="#quan-tri">Dành cho quản trị</a>}
      </nav>

      <section className="card" id="thue">
        <h2>🎮 Thuê account</h2>
        <ol>
          <li>
            Vào <Link to="/listings">Tìm account</Link>, chọn game và lọc theo giá.
          </li>
          <li>Mở account bạn thích, xem mô tả, giá theo giờ, tiền cọc và lịch đã có người đặt.</li>
          <li>Chọn giờ bắt đầu và thời lượng, bấm đặt thuê. Hệ thống tính sẵn tổng tiền (tiền thuê + cọc).</li>
          <li>Thanh toán trong thời gian giữ chỗ, quá hạn đơn sẽ tự hủy.</li>
          <li>Nếu chủ account cần duyệt, đơn ở trạng thái “Chờ chủ account duyệt”. Không được duyệt thì bạn được hoàn đủ tiền.</li>
          <li>
            Đến giờ, vào <Link to="/orders">Đơn của tôi</Link>, mở đơn và bấm “Xem thông tin đăng nhập”.
          </li>
          <li>Đăng nhập được thì bấm “Xác nhận đã nhận account”. Hết giờ, lượt thuê tự kết thúc.</li>
        </ol>
      </section>

      <section className="card" id="cho-thue">
        <h2>💼 Cho thuê account</h2>
        <ol>
          <li>
            Vào <Link to="/my-listings">Account cho thuê</Link>, thêm account game. Bạn cần xác nhận mình sở hữu email, số điện thoại khôi phục và xác thực 2 lớp.
          </li>
          <li>Đội ngũ VieGG xác minh thông tin khôi phục của account.</li>
          <li>Tạo bài đăng (listing) ở dạng nháp. Trước khi đặt giá, bạn sẽ thấy các rủi ro khi cho thuê account của game đó và cần xác nhận đã đọc. Nên đặt cọc đủ bù thiệt hại lớn nhất có thể xảy ra.</li>
          <li>Bấm “Gửi duyệt”. Khi được duyệt, bài đăng chuyển sang “Đang hiển thị” và người thuê có thể đặt.</li>
          <li>
            Có đơn mới, bạn nhận thông báo. Nếu không bật “Tự động nhận đơn”, hãy duyệt đơn trong thời hạn.
          </li>
          <li>Sau mỗi lượt thuê, bấm “Đổi mật khẩu” cho account trước hạn để không bị hủy đơn kế tiếp.</li>
        </ol>
      </section>

      <section className="card" id="vi">
        <h2>💰 Ví và tiền cọc</h2>
        <dl className="kv glossary">
          <dt>Số dư ví</dt>
          <dd>
            Tiền bạn nạp, tiền được hoàn (cọc, hủy đơn, khiếu nại) và doanh thu cho thuê đã giải ngân. Dùng để thuê
            (tiền thuê và cọc được trừ từ ví khi đặt) hoặc rút về ngân hàng.
          </dd>
          <dt>Đang giữ (chờ đối soát)</dt>
          <dd>Doanh thu của lượt thuê vừa xong, được giữ một thời gian để xử lý khiếu nại nếu có.</dd>
          <dt>Đang giữ (khiếu nại)</dt>
          <dd>Doanh thu của đơn đang có khiếu nại, chờ kết quả phân xử.</dd>
          <dt>Đang chuyển khoản</dt>
          <dd>Các yêu cầu rút tiền đang được bộ phận tài chính xử lý.</dd>
        </dl>
        <p className="muted" style={{ marginTop: '0.75rem' }}>
          Khi đi thuê, các khoản cọc của bạn được liệt kê ở cuối trang <Link to="/wallet">Ví</Link>.
        </p>
      </section>

      <section className="card" id="khieu-nai">
        <h2>🛟 Khiếu nại</h2>
        <ul>
          <li>
            <strong>Người thuê</strong> có thể báo “Không đăng nhập được” hoặc “Account không đúng mô tả”.
          </li>
          <li>
            <strong>Chủ account</strong> có thể báo thiệt hại hoặc account bị chiếm sau lượt thuê.
          </li>
          <li>Khiếu nại được mở trong trang chi tiết đơn khi đơn đang thuê hoặc vừa kết thúc. Hãy đính kèm link ảnh hoặc video làm bằng chứng.</li>
          <li>Bộ phận hỗ trợ sẽ phân xử: có thể khấu trừ cọc, hoàn tiền thuê, hoặc bác khiếu nại.</li>
        </ul>
      </section>

      <section className="card stack" id="trang-thai">
        <h2>📖 Giải thích trạng thái</h2>
        <p className="muted">Trên toàn hệ thống, bạn có thể rê chuột lên một nhãn trạng thái để xem giải thích này.</p>
        {GLOSSARY.map((g) => (
          <div key={g.title}>
            <h3 style={{ fontSize: '1rem' }}>{g.title}</h3>
            <dl className="kv glossary">
              {Object.entries(g.dict).map(([code, def]) => (
                <div key={code} style={{ display: 'contents' }}>
                  <dt>{g.badge ? <StatusBadge status={code} dict={g.dict} /> : def.label}</dt>
                  <dd>{def.hint ?? ''}</dd>
                </div>
              ))}
            </dl>
          </div>
        ))}
      </section>

      <section className="card" id="hoi-dap">
        <h2>❓ Hỏi đáp</h2>
        {FAQ.map((f) => (
          <details key={f.q} className="faq">
            <summary>{f.q}</summary>
            <p>{f.a}</p>
          </details>
        ))}
      </section>

      {isStaff && (
        <section className="card" id="quan-tri">
          <h2>🛠️ Dành cho quản trị</h2>
          <p className="muted">Bạn chỉ thấy những mục mình được phân quyền.</p>
          <dl className="kv glossary">
            <dt>Kiểm duyệt</dt>
            <dd>Duyệt hoặc từ chối bài đăng mới, xác minh thông tin khôi phục account, đình chỉ bài đăng vi phạm.</dd>
            <dt>Khiếu nại</dt>
            <dd>Xem bằng chứng hai bên và quyết định khấu trừ cọc, hoàn tiền thuê hoặc bác khiếu nại.</dd>
            <dt>Game</dt>
            <dd>Thêm game mới và cài đặt chính sách: mức cọc, phí nền tảng, thời lượng thuê, quy định hủy.</dd>
            <dt>Người dùng</dt>
            <dd>Tra cứu người dùng, khóa / hạn chế, đổi mức rủi ro, duyệt xác minh danh tính, gán vai trò.</dd>
            <dt>Phê duyệt</dt>
            <dd>Các thao tác nhạy cảm cần hai người: một người tạo yêu cầu, người khác duyệt.</dd>
            <dt>Tài chính</dt>
            <dd>Chi trả yêu cầu rút tiền, kiểm tra đối soát sổ cái và xem bút toán của từng đơn.</dd>
            <dt>Phân quyền</dt>
            <dd>Tạo vai trò và chọn quyền cho từng vai trò.</dd>
          </dl>
        </section>
      )}
    </div>
  );
}
