import { hintOf, labelOf, LISTING_STATUS, ORDER_STATUS, toneOf, type LabelDict } from '../lib/labels';

/** Order and listing statuses share one badge by default; pass `dict` for any other kind of status. */
const DEFAULT_DICT: LabelDict = { ...LISTING_STATUS, ...ORDER_STATUS };

export function StatusBadge({ status, dict = DEFAULT_DICT }: { status: string; dict?: LabelDict }) {
  const tone = toneOf(dict, status);
  return (
    <span className={tone ? `badge badge-${tone}` : 'badge'} title={hintOf(dict, status)}>
      {labelOf(dict, status)}
    </span>
  );
}
