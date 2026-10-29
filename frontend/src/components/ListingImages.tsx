import { useEffect, useRef, useState, type ChangeEvent } from 'react';
import { api } from '../api/client';
import type { Listing } from '../api/types';
import { compressImage, MAX_LISTING_IMAGES } from '../lib/image';

/**
 * Loads one listing image through the API client, so the owner and staff can also see images of listings that
 * are not public yet (a plain <img src> cannot send the bearer token).
 */
function useImageUrl(listingId: string, imageId: string | undefined): string | null {
  const [url, setUrl] = useState<string | null>(null);
  useEffect(() => {
    if (!imageId) {
      setUrl(null);
      return;
    }
    let objectUrl: string | null = null;
    let cancelled = false;
    api<Blob>(`/listings/${listingId}/images/${imageId}`, { responseType: 'blob' })
      .then((blob) => {
        if (cancelled) return;
        objectUrl = URL.createObjectURL(blob);
        setUrl(objectUrl);
      })
      .catch(() => !cancelled && setUrl(null));
    return () => {
      cancelled = true;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [listingId, imageId]);
  return url;
}

export function ListingImg({
  listingId,
  imageId,
  alt,
  className,
}: {
  listingId: string;
  imageId: string;
  alt: string;
  className?: string;
}) {
  const url = useImageUrl(listingId, imageId);
  return url ? (
    <img src={url} alt={alt} className={className} loading="lazy" />
  ) : (
    <div className={`${className ?? ''} img-placeholder`} aria-hidden="true" />
  );
}

/** Large image with a thumbnail strip, for the listing detail page. */
export function ListingGallery({ listing }: { listing: Listing }) {
  const [current, setCurrent] = useState(0);
  const ids = listing.imageIds ?? [];
  if (ids.length === 0) return null;
  const active = ids[Math.min(current, ids.length - 1)];
  return (
    <div className="gallery">
      <ListingImg listingId={listing.id} imageId={active} alt={`Ảnh account: ${listing.title}`} className="gallery-main" />
      {ids.length > 1 && (
        <div className="gallery-thumbs" role="group" aria-label="Chọn ảnh">
          {ids.map((id, i) => (
            <button
              key={id}
              type="button"
              className={`gallery-thumb ${id === active ? 'active' : ''}`}
              onClick={() => setCurrent(i)}
              aria-label={`Ảnh ${i + 1}`}
              aria-pressed={id === active}
            >
              <ListingImg listingId={listing.id} imageId={id} alt="" />
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

/** Owner view: thumbnails with delete, plus an upload button (photos are shrunk to JPEG ≤ 1 MB first). */
export function ListingImageManager({
  listing: l,
  onChanged,
  onError,
}: {
  listing: Listing;
  onChanged: (msg: string) => void;
  onError: (err: unknown) => void;
}) {
  const [busy, setBusy] = useState(false);
  const [progress, setProgress] = useState<string | null>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const ids = l.imageIds ?? [];
  const room = MAX_LISTING_IMAGES - ids.length;
  const readOnly = l.status === 'SUSPENDED';

  const onPick = async (e: ChangeEvent<HTMLInputElement>) => {
    const files = Array.from(e.target.files ?? []).slice(0, room);
    e.target.value = '';
    if (files.length === 0) return;
    if (
      l.status === 'ACTIVE' &&
      !window.confirm('Thêm ảnh sẽ đưa listing đang hiển thị về trạng thái chờ duyệt. Tiếp tục?')
    ) {
      return;
    }
    setBusy(true);
    let added = 0;
    try {
      for (const [i, file] of files.entries()) {
        setProgress(`Đang tải ảnh ${i + 1}/${files.length}…`);
        const form = new FormData();
        form.append('file', await compressImage(file), 'account.jpg');
        await api(`/listings/${l.id}/images`, { method: 'POST', body: form });
        added++;
      }
    } catch (err) {
      onError(err);
    } finally {
      setBusy(false);
      setProgress(null);
      if (added > 0) onChanged(`Đã thêm ${added} ảnh.`);
    }
  };

  const onRemove = async (imageId: string) => {
    if (!window.confirm('Xóa ảnh này?')) return;
    setBusy(true);
    try {
      await api(`/listings/${l.id}/images/${imageId}`, { method: 'DELETE' });
      onChanged('Đã xóa ảnh.');
    } catch (err) {
      onError(err);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="stack" style={{ gap: '0.5rem' }}>
      <div className="image-grid">
        {ids.map((id, i) => (
          <div key={id} className="image-tile">
            <ListingImg listingId={l.id} imageId={id} alt={`Ảnh ${i + 1}`} />
            {!readOnly && (
              <button
                type="button"
                className="image-remove"
                disabled={busy}
                onClick={() => onRemove(id)}
                aria-label={`Xóa ảnh ${i + 1}`}
              >
                ×
              </button>
            )}
          </div>
        ))}
        {!readOnly && room > 0 && (
          <button type="button" className="image-tile image-add" disabled={busy} onClick={() => inputRef.current?.click()}>
            <span aria-hidden="true">＋</span>
            <span>{busy ? 'Đang tải…' : 'Thêm ảnh'}</span>
          </button>
        )}
      </div>
      <input ref={inputRef} type="file" accept="image/jpeg,image/png,image/webp" multiple hidden onChange={onPick} />
      <span className="field-hint" aria-live="polite">
        {progress ??
          `${ids.length}/${MAX_LISTING_IMAGES} ảnh. Ảnh chụp skin, rank, tướng… giúp người thuê chọn nhanh hơn. Đừng để lộ tên đăng nhập hay email.`}
      </span>
    </div>
  );
}
