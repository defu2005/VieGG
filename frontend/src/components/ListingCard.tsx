import { Link } from 'react-router-dom';
import type { Listing } from '../api/types';
import { attributeTags } from '../lib/attributes';
import { formatVnd } from '../lib/format';
import { priceSummary } from '../lib/pricing';
import { ListingImg } from './ListingImages';

export function ListingCard({ listing: l, gameName }: { listing: Listing; gameName?: string }) {
  const tags = attributeTags(l.attributes);
  const price = priceSummary(l);
  return (
    <Link to={`/listings/${l.id}`} className="card listing-card">
      {l.imageIds?.length > 0 ? (
        <ListingImg listingId={l.id} imageId={l.imageIds[0]} alt={`Ảnh account: ${l.title}`} className="listing-card-cover" />
      ) : (
        <div className="listing-card-cover cover-fallback" aria-hidden="true">
          {(gameName ?? l.title).trim().slice(0, 2).toUpperCase()}
        </div>
      )}
      <div className="row spread">
        {gameName ? <span className="badge">{gameName}</span> : <span />}
        {l.autoAccept && <span className="tag tag-hot">⚡ Nhận đơn ngay</span>}
      </div>
      <h2>{l.title}</h2>
      {tags.length > 0 && (
        <div className="tags">
          {tags.map((t) => (
            <span key={t} className="tag">
              {t}
            </span>
          ))}
        </div>
      )}
      <p className="muted" style={{ margin: 0, minHeight: '2.6em' }}>
        {l.description ? l.description.slice(0, 90) : 'Chưa có mô tả.'}
      </p>
      <div className="row spread listing-card-foot">
        <span className="price">
          {price.from && <small>từ </small>}
          {formatVnd(price.amount)}
          <small> / {price.unit}</small>
        </span>
        <span className="muted">Cọc {formatVnd(l.depositAmount)}</span>
      </div>
    </Link>
  );
}

export function ListingSkeletons({ count = 6 }: { count?: number }) {
  return (
    <div className="grid" aria-busy="true" aria-label="Đang tải">
      {Array.from({ length: count }, (_, i) => (
        <div key={i} className="skeleton" />
      ))}
    </div>
  );
}
