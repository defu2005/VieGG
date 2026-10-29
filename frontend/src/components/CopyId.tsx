import { useState } from 'react';

/** Shows the first 8 characters of an id and copies the full id on click. */
export function CopyId({ id, label }: { id: string; label?: string }) {
  const [copied, setCopied] = useState(false);

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(id);
    } catch {
      // Clipboard API blocked (e.g. plain http on a LAN IP): fall back to a prompt the user can copy from.
      window.prompt('Copy mã:', id);
      return;
    }
    setCopied(true);
    window.setTimeout(() => setCopied(false), 1500);
  };

  return (
    <button type="button" className="link-btn copy-id" title={`${id} — bấm để copy`} onClick={copy}>
      {label ?? id.slice(0, 8)} <span aria-hidden="true">{copied ? '✓' : '⧉'}</span>
      <span className="sr-only">{copied ? 'Đã copy' : 'Copy mã đầy đủ'}</span>
    </button>
  );
}
