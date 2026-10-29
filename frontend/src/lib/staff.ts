/**
 * Staff (back-office) menu. Staff accounts use only these pages: they do not rent, list accounts or use a wallet.
 * Each link shows when the user holds at least one of its permissions.
 */
export const STAFF_LINKS: { to: string; label: string; anyOf: string[] }[] = [
  { to: '/admin/listings', label: 'Kiểm duyệt', anyOf: ['listing:review_queue'] },
  { to: '/support/disputes', label: 'Khiếu nại', anyOf: ['dispute:read_any'] },
  { to: '/tickets', label: 'Hỗ trợ', anyOf: ['helpdesk:handle'] },
  { to: '/admin/games', label: 'Game', anyOf: ['game:read_hidden'] },
  { to: '/admin/users', label: 'Người dùng', anyOf: ['user:read'] },
  { to: '/admin/approvals', label: 'Phê duyệt', anyOf: ['approval:list', 'game:change_tos'] },
  { to: '/finance', label: 'Tài chính', anyOf: ['withdrawal:list_all', 'ledger:read'] },
  { to: '/admin/roles', label: 'Phân quyền', anyOf: ['role:read'] },
  { to: '/admin/settings', label: 'Cấu hình', anyOf: ['setting:read'] },
];
