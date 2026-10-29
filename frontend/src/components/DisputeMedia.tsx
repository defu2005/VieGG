import { useEffect, useRef, useState, type ChangeEvent } from 'react';
import { api } from '../api/client';
import { compressImage } from '../lib/image';

/** Same limits as the backend (EvidenceMedia / DisputeService). */
export const MAX_DISPUTE_FILES = 10;
const MAX_IMAGE_BYTES = 5 * 1024 * 1024;
const MAX_VIDEO_BYTES = 20 * 1024 * 1024;
const SERVER_IMAGE_TYPES = ['image/jpeg', 'image/png', 'image/webp'];
const VIDEO_TYPES = ['video/mp4', 'video/quicktime', 'video/webm'];
const ACCEPT = [...SERVER_IMAGE_TYPES, 'image/*', ...VIDEO_TYPES, '.mov', '.mp4', '.webm'].join(',');

export interface AttachmentEvidence {
  attachmentId: string;
  contentType: string;
  note?: string;
}

export function isAttachment(e: Record<string, unknown>): e is Record<string, unknown> & AttachmentEvidence {
  return typeof e.attachmentId === 'string' && typeof e.contentType === 'string';
}

function formatSize(bytes: number): string {
  return bytes >= 1024 * 1024 ? `${(bytes / 1024 / 1024).toFixed(1)} MB` : `${Math.ceil(bytes / 1024)} KB`;
}

function isVideoFile(f: File): boolean {
  return f.type.startsWith('video/') || /\.(mp4|mov|webm)$/i.test(f.name);
}

/** Checks a picked file before upload; returns an error message, or null when it can be sent. */
function checkFile(f: File): string | null {
  if (isVideoFile(f)) {
    if (f.type && !VIDEO_TYPES.includes(f.type)) return `${f.name}: chỉ nhận video MP4, MOV hoặc WebM.`;
    if (f.size > MAX_VIDEO_BYTES) return `${f.name}: video tối đa 20 MB (tệp này ${formatSize(f.size)}).`;
    return null;
  }
  if (!f.type.startsWith('image/')) return `${f.name}: chỉ nhận ảnh hoặc video.`;
  return null; // large or unusual images (HEIC…) are re-encoded to JPEG before upload
}

/** Original file when the server accepts it as is; otherwise a JPEG copy (evidence stays readable, ≤ 1 MB). */
async function toUploadable(f: File): Promise<Blob> {
  if (isVideoFile(f)) return f;
  if (SERVER_IMAGE_TYPES.includes(f.type) && f.size <= MAX_IMAGE_BYTES) return f;
  return compressImage(f);
}

/**
 * Uploads files one by one to a dispute. The note goes with the first file only.
 * Returns how many were sent; throws on the first failure (earlier files stay uploaded, `onFileDone` fires per file).
 */
export async function uploadDisputeFiles(
  disputeId: string,
  files: File[],
  note?: string,
  onProgress?: (msg: string) => void,
  onFileDone?: () => void,
): Promise<number> {
  let sent = 0;
  for (const [i, f] of files.entries()) {
    onProgress?.(`Đang tải tệp ${i + 1}/${files.length}: ${f.name}…`);
    const form = new FormData();
    form.append('file', await toUploadable(f), f.name);
    if (i === 0 && note) form.append('note', note);
    await api(`/disputes/${disputeId}/attachments`, { method: 'POST', body: form });
    sent++;
    onFileDone?.();
  }
  return sent;
}

/** File chooser with a removable list of picked photos/videos. */
export function DisputeFilePicker({
  id,
  files,
  onChange,
  room = MAX_DISPUTE_FILES,
  disabled,
}: {
  id: string;
  files: File[];
  onChange: (files: File[]) => void;
  room?: number;
  disabled?: boolean;
}) {
  const inputRef = useRef<HTMLInputElement>(null);
  const [error, setError] = useState<string | null>(null);
  const left = room - files.length;

  const onPick = (e: ChangeEvent<HTMLInputElement>) => {
    const picked = Array.from(e.target.files ?? []);
    e.target.value = '';
    const errors: string[] = [];
    const ok = picked.filter((f) => {
      const err = checkFile(f);
      if (err) errors.push(err);
      return !err;
    });
    if (ok.length > left) errors.push(`Chỉ thêm được ${Math.max(left, 0)} tệp nữa (tối đa ${MAX_DISPUTE_FILES}).`);
    setError(errors.length ? errors.join(' ') : null);
    onChange([...files, ...ok.slice(0, Math.max(left, 0))]);
  };

  return (
    <div className="field">
      <label htmlFor={id}>Ảnh / video bằng chứng</label>
      {files.length > 0 && (
        <ul className="file-list">
          {files.map((f, i) => (
            <li key={`${f.name}-${i}`}>
              <span aria-hidden="true">{isVideoFile(f) ? '🎬' : '🖼️'}</span>
              <span className="file-name">{f.name}</span>
              <span className="muted">{formatSize(f.size)}</span>
              <button
                type="button"
                className="btn btn-ghost btn-sm"
                disabled={disabled}
                onClick={() => onChange(files.filter((_, j) => j !== i))}
                aria-label={`Bỏ ${f.name}`}
              >
                Bỏ
              </button>
            </li>
          ))}
        </ul>
      )}
      {left > 0 && (
        <div>
          <button type="button" className="btn" disabled={disabled} onClick={() => inputRef.current?.click()}>
            ＋ Chọn ảnh hoặc video
          </button>
        </div>
      )}
      <input ref={inputRef} id={id} type="file" accept={ACCEPT} multiple hidden onChange={onPick} />
      <span className="field-hint">Ảnh JPEG/PNG/WebP (tối đa 5 MB) · Video MP4/MOV/WebM (tối đa 20 MB) · Tối đa {room} tệp.</span>
      {error && <p className="error">{error}</p>}
    </div>
  );
}

/** Loads an attachment through the API client (the bearer token cannot ride on a plain src). */
function useAttachmentUrl(disputeId: string, attachmentId: string): { url: string | null; failed: boolean } {
  const [url, setUrl] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    let objectUrl: string | null = null;
    let cancelled = false;
    setUrl(null);
    setFailed(false);
    api<Blob>(`/disputes/${disputeId}/attachments/${attachmentId}`, { responseType: 'blob' })
      .then((blob) => {
        if (cancelled) return;
        objectUrl = URL.createObjectURL(blob);
        setUrl(objectUrl);
      })
      .catch(() => !cancelled && setFailed(true));
    return () => {
      cancelled = true;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [disputeId, attachmentId]);
  return { url, failed };
}

/** Inline photo (click to open full size) or video player. */
export function DisputeAttachmentView({ disputeId, attachment }: { disputeId: string; attachment: AttachmentEvidence }) {
  const { url, failed } = useAttachmentUrl(disputeId, attachment.attachmentId);
  const video = attachment.contentType.startsWith('video/');
  if (failed) return <div className="evidence-media evidence-media-failed">Không tải được tệp.</div>;
  if (!url) return <div className="evidence-media img-placeholder" aria-label="Đang tải…" />;
  return video ? (
    <video className="evidence-media" src={url} controls preload="metadata" />
  ) : (
    <a href={url} target="_blank" rel="noreferrer noopener" className="evidence-media">
      <img src={url} alt="Ảnh bằng chứng" loading="lazy" />
    </a>
  );
}
