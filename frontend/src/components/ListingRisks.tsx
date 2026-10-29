import type { GameRisk, Listing } from '../api/types';
import { formatVnd } from '../lib/format';
import { netText, RISK_FEE_NOTICE } from '../lib/riskFee';

function amountOf(listing: Listing, risk: GameRisk): number | null {
  return listing.riskTerms?.find((t) => t.riskId === risk.id)?.amount ?? null;
}

function windowText(r: GameRisk): string {
  if (r.complaintWindowMinutes == null) return '';
  return r.side === 'RENTER'
    ? ` · khiếu nại trong ${r.complaintWindowMinutes} phút sau khi xem thông tin tài khoản`
    : ` · chủ account khiếu nại trong ${r.complaintWindowMinutes} phút sau khi kết thúc`;
}

/** What the deposit covers and what the renter gets back if something goes wrong, per game risk. */
export function ListingRisks({ listing, risks }: { listing: Listing; risks: GameRisk[] }) {
  if (risks.length === 0) return null;
  const owner = risks.filter((r) => r.side === 'OWNER');
  const renter = risks.filter((r) => r.side === 'RENTER');
  // Listings made before risks existed have no terms: the whole deposit covers every owner risk.
  const legacy = (listing.riskTerms ?? []).length === 0;
  return (
    <>
      <h2>Tiền cọc và đền bù</h2>
      <p className="muted" style={{ margin: 0 }}>{RISK_FEE_NOTICE}</p>
      {owner.length > 0 && (
        <>
          <p className="muted" style={{ margin: 0 }}>
            Tiền cọc {formatVnd(listing.depositAmount)} được hoàn lại nếu không có sự cố. Nếu bạn gây ra sự cố dưới đây, chủ account có
            thể khiếu nại và được khấu trừ tối đa:
          </p>
          {owner.some((r) => r.stackable ?? true) && (
            <>
              <strong>Cộng dồn (các sự cố cùng xảy ra thì cộng lại)</strong>
              <ul className="risk-list">
                {owner
                  .filter((r) => r.stackable ?? true)
                  .map((r) => {
                    const a = amountOf(listing, r);
                    return (
                      <li key={r.id}>
                        {r.name}
                        {r.ownerDefined && <span className="tag" style={{ marginLeft: '0.3rem' }}>chủ account thêm</span>}
                        : {a != null ? formatVnd(a) : legacy ? 'trong phạm vi tiền cọc' : 'chủ account không yêu cầu đền bù'}
                        {windowText(r)}
                      </li>
                    );
                  })}
              </ul>
            </>
          )}
          {owner.some((r) => !(r.stackable ?? true)) && (
            <>
              <strong>Không cộng dồn (nếu xảy ra, bạn chỉ trả đúng khoản này)</strong>
              <ul className="risk-list">
                {owner
                  .filter((r) => !(r.stackable ?? true))
                  .map((r) => {
                    const a = amountOf(listing, r);
                    return (
                      <li key={r.id}>
                        {r.name}
                        {r.ownerDefined && <span className="tag" style={{ marginLeft: '0.3rem' }}>chủ account thêm</span>}
                        : {a != null ? formatVnd(a) : legacy ? 'trong phạm vi tiền cọc' : 'chủ account không yêu cầu đền bù'}
                        {windowText(r)}
                      </li>
                    );
                  })}
              </ul>
            </>
          )}
        </>
      )}
      {renter.length > 0 && (
        <>
          <p className="muted" style={{ margin: 0 }}>Nếu account có vấn đề, bạn khiếu nại để được:</p>
          <ul className="risk-list">
            {renter.map((r) => {
              const a = amountOf(listing, r);
              return (
                <li key={r.id}>
                  {r.name}
                  {r.ownerDefined && <span className="tag" style={{ marginLeft: '0.3rem' }}>chủ account thêm</span>}
                  : hoàn tiền thuê{a != null ? ` + đền bù tối đa ${formatVnd(a)} (bạn ${netText(a)})` : ''}
                  {windowText(r)}
                </li>
              );
            })}
          </ul>
        </>
      )}
    </>
  );
}
