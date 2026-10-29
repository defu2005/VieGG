/** Listing attributes are a free-form map on the backend; the UI edits them as "key: value" lines. */

export function parseAttributes(text: string): Record<string, string> {
  const result: Record<string, string> = {};
  for (const line of text.split('\n')) {
    const i = line.indexOf(':');
    if (i < 1) continue;
    const key = line.slice(0, i).trim();
    const value = line.slice(i + 1).trim();
    if (key && value) result[key] = value;
  }
  return result;
}

export function formatAttributes(attrs: Record<string, unknown> | null | undefined): string {
  if (!attrs) return '';
  return Object.entries(attrs)
    .filter(([, v]) => ['string', 'number', 'boolean'].includes(typeof v))
    .map(([k, v]) => `${k}: ${v}`)
    .join('\n');
}

/** Short display tags such as "Rank: Kim Cương", skipping non-scalar values. */
export function attributeTags(attrs: Record<string, unknown> | null | undefined, max = 4): string[] {
  if (!attrs) return [];
  return Object.entries(attrs)
    .filter(([, v]) => ['string', 'number', 'boolean'].includes(typeof v) && String(v).trim() !== '')
    .slice(0, max)
    .map(([k, v]) => `${k}: ${v}`);
}
