import type { GameView } from '../api/types';
import { formatVnd } from '../lib/format';

/** Used when the admin has not written any risk notes for the game yet. */
const DEFAULT_RISKS = [
  'Người thuê làm mất hoặc bán vật phẩm, tiền trong game.',
  'Người thuê đổi email, số điện thoại hoặc thông tin khôi phục của account.',
  'Account bị báo cáo hoặc bị nhà phát hành khóa tạm thời hay vĩnh viễn.',
  'Mất hẳn account.',
];

/** Risks of renting out one game's account, shown to the owner before they set the deposit. */
export function GameRiskPanel({ game }: { game: GameView }) {
  if (game.risks?.length) {
    const owner = game.risks.filter((r) => r.side === 'OWNER');
    const renter = game.risks.filter((r) => r.side === 'RENTER');
    return (
      <section className="risk-panel tone-warn" aria-label={`Rủi ro của game ${game.name}`}>
        <strong>Rủi ro có thể xảy ra khi cho thuê {game.name}</strong>
        {owner.length > 0 && (
          <>
            <span>Rủi ro của bạn (người thuê làm hại account) — với mỗi rủi ro, bạn chọn có cần người thuê đền bù (đặt cọc) không:</span>
            <ul className="risk-list">
              {owner.map((r) => (
                <li key={r.id}>
                  {r.name} ({(r.stackable ?? true) ? 'cộng dồn' : 'không cộng dồn'})
                  {r.recommendation && ` · khuyến nghị: ${r.recommendation}`}
                </li>
              ))}
            </ul>
          </>
        )}
        {renter.length > 0 && (
          <>
            <span>Rủi ro của người thuê — với mỗi rủi ro, bạn chọn có cam kết đền bù không; nếu không, người thuê chỉ được hoàn tiền thuê:</span>
            <ul className="risk-list">
              {renter.map((r) => (
                <li key={r.id}>
                  {r.name}
                  {r.complaintWindowMinutes != null && ` · khiếu nại trong ${r.complaintWindowMinutes} phút sau khi xem tài khoản`}
                </li>
              ))}
            </ul>
          </>
        )}
        <p>
          Tiền cọc của đơn = max(tổng các khoản cộng dồn, khoản không cộng dồn lớn nhất), phải nằm trong khoảng{' '}
          {formatVnd(game.depositMin)} – {formatVnd(game.depositMax)}.
        </p>
      </section>
    );
  }
  const notes = (game.riskNotes ?? '')
    .split('\n')
    .map((line) => line.replace(/^\s*[-•*]\s*/, '').trim())
    .filter(Boolean);
  const risks = notes.length > 0 ? notes : DEFAULT_RISKS;

  return (
    <section className="risk-panel tone-warn" aria-label={`Rủi ro của game ${game.name}`}>
      <strong>Rủi ro có thể xảy ra khi cho thuê {game.name}</strong>
      <ul className="risk-list">
        {risks.map((r) => (
          <li key={r}>{r}</li>
        ))}
      </ul>
      <p>
        <strong>Lời khuyên:</strong> hãy đặt tiền cọc đủ để bù thiệt hại lớn nhất có thể xảy ra trong các rủi ro trên. Game này cho
        phép cọc từ {formatVnd(game.depositMin)} đến {formatVnd(game.depositMax)}.
      </p>
    </section>
  );
}
