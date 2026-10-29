import { Link } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { USER_LOOKUP_PERMISSIONS, useUserBrief } from '../lib/userDirectory';
import { CopyId } from './CopyId';

/**
 * A user referenced by id, shown the way staff recognise people: name and email, a link to the user's page
 * when the viewer may open it (user:read), and a button that copies the full id.
 */
export function UserRef({ id, showEmail = true }: { id: string | null | undefined; showEmail?: boolean }) {
  const { can } = useAuth();
  const canLookup = can(...USER_LOOKUP_PERMISSIONS);
  const brief = useUserBrief(id, canLookup);
  if (!id) return <span className="muted">—</span>;

  const name = brief ? brief.displayName : brief === undefined ? '…' : id.slice(0, 8);
  const label = can('user:read') ? (
    <Link to={`/admin/users?id=${id}`} title={brief?.email ?? id}>
      {name}
    </Link>
  ) : (
    <span title={brief?.email ?? id}>{name}</span>
  );

  return (
    <span className="user-ref">
      {label}
      {brief && showEmail && <span className="muted"> · {brief.email}</span>}
      {brief && brief.status !== 'ACTIVE' && <span className="muted"> ({brief.status === 'SUSPENDED' ? 'đã khoá' : 'hạn chế'})</span>}{' '}
      <CopyId id={id} label="mã" />
    </span>
  );
}
