import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '../api/client';
import type { PageResponse } from '../api/types';

/**
 * Previous/next controls for a paged list. Renders nothing when everything fits on one page.
 * `page` is 0-based, as in the API.
 */
export function Pager({
  result,
  page,
  onPage,
}: {
  result: Pick<PageResponse<unknown>, 'totalPages' | 'totalElements'> | null | undefined;
  page: number;
  onPage: (page: number) => void;
}) {
  if (!result || result.totalPages <= 1) return null;
  const last = result.totalPages - 1;
  return (
    <div className="row spread" role="navigation" aria-label="Phân trang">
      <button type="button" className="btn" disabled={page <= 0} onClick={() => onPage(page - 1)}>
        ‹ Trang trước
      </button>
      <span className="muted">
        Trang {Math.min(page, last) + 1} / {result.totalPages} · {result.totalElements.toLocaleString('vi-VN')} mục
      </span>
      <button type="button" className="btn" disabled={page >= last} onClick={() => onPage(page + 1)}>
        Trang sau ›
      </button>
    </div>
  );
}

/** If a page became empty (e.g. its last item was approved), the page to go back to; otherwise null. */
export function pageAfterShrink(result: PageResponse<unknown>, page: number): number | null {
  return result.content.length === 0 && page > 0 && result.totalPages > 0 ? result.totalPages - 1 : null;
}

/**
 * Loads one page of `path` (which may already carry filters in its query string).
 * Changing `path` goes back to the first page; `null` skips loading.
 */
export function usePaged<T>(path: string | null, { size = 20, onError }: { size?: number; onError?: (err: unknown) => void } = {}) {
  const [page, setPage] = useState(0);
  const [result, setResult] = useState<PageResponse<T> | null>(null);
  const [reloadKey, setReloadKey] = useState(0);
  const onErrorRef = useRef(onError);
  onErrorRef.current = onError;

  const lastPath = useRef(path);
  if (lastPath.current !== path) {
    lastPath.current = path;
    if (page !== 0) setPage(0);
    if (result !== null) setResult(null);
  }

  useEffect(() => {
    if (path == null) return;
    let cancelled = false;
    const sep = path.includes('?') ? '&' : '?';
    api<PageResponse<T>>(`${path}${sep}page=${page}&size=${size}`)
      .then((r) => {
        if (cancelled) return;
        const back = pageAfterShrink(r, page);
        if (back != null) setPage(back);
        else setResult(r);
      })
      .catch((err) => {
        if (!cancelled) onErrorRef.current?.(err);
      });
    return () => {
      cancelled = true;
    };
  }, [path, page, size, reloadKey]);

  const reload = useCallback(() => setReloadKey((k) => k + 1), []);
  return { result, items: result?.content ?? null, page, setPage, reload };
}
