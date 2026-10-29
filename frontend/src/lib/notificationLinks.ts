import type { NotificationItem } from '../api/types';
import {
  APPROVAL_ACTION,
  APPROVAL_STATUS,
  CANCEL_REASON,
  DEPOSIT_STATUS,
  labelOf,
  LISTING_STATUS,
  SECURITY_STATE,
  USER_STATUS,
} from './labels';

/** A button on a notification. `to` is an in-app path; a `#hash` scrolls to that section. */
export interface NotificationAction {
  label: string;
  to: string;
}

/** A small "label: value" line under the title. Kind tells the page how to show the value. */
export interface NotificationDetail {
  label: string;
  value: string;
  kind?: 'money' | 'time' | 'countdown' | 'text';
}

export interface NotificationView {
  /** Where clicking the notification goes (same as the first action). */
  primary: NotificationAction | null;
  secondary: NotificationAction[];
  details: NotificationDetail[];
}

interface Viewer {
  id: string;
  can: (...permissions: string[]) => boolean;
}

function str(data: Record<string, unknown>, key: string): string | null {
  const v = data[key];
  return v == null || v === '' ? null : String(v);
}

function num(data: Record<string, unknown>, key: string): string | null {
  const v = data[key];
  return typeof v === 'number' ? String(v) : null;
}

/** Works out where a notification leads and what to show for it, from its type and stored event data. */
export function viewNotification(n: NotificationItem, me: Viewer): NotificationView {
  const d = n.data ?? {};
  const orderId = str(d, 'orderId');
  const isOwner = str(d, 'ownerId') === me.id || str(d, 'sellerId') === me.id;
  const isRenter = str(d, 'renterId') === me.id;
  const order = (hash = '', label = 'Xem đơn'): NotificationAction | null =>
    orderId ? { label, to: `/orders/${orderId}${hash}` } : { label: 'Đơn của tôi', to: '/orders' };

  let primary: NotificationAction | null = null;
  const secondary: NotificationAction[] = [];
  const details: NotificationDetail[] = [];
  const money = (label: string, key: string) => {
    const v = num(d, key);
    if (v) details.push({ label, value: v, kind: 'money' });
  };
  const time = (label: string, key: string, kind: 'time' | 'countdown' = 'time') => {
    const v = str(d, key);
    if (v) details.push({ label, value: v, kind });
  };
  const text = (label: string, value: string | null) => {
    if (value) details.push({ label, value, kind: 'text' });
  };

  switch (n.type) {
    // ------------------------------------------------------------------ orders
    case 'OrderCreated':
      primary = order('', isRenter ? 'Thanh toán ngay' : 'Xem đơn');
      money('Cần thanh toán', 'amountToPay');
      time('Bắt đầu', 'startAt');
      break;
    case 'OrderPaid':
      primary = order();
      money('Tiền thuê', 'rentAmount');
      money('Tiền cọc', 'depositAmount');
      break;
    case 'OrderAwaitingApproval':
      primary = order('#approve', 'Duyệt đơn');
      time('Hạn duyệt còn', 'approvalExpiresAt', 'countdown');
      break;
    case 'OrderConfirmed':
      primary = order();
      time('Bắt đầu', 'startAt');
      if (d.autoAccepted === true) text('Ghi chú', 'Tự động xác nhận');
      break;
    case 'OrderRejected':
    case 'OrderExpired':
    case 'OrderCancelled':
      primary = order();
      text('Lý do', str(d, 'reason') && labelOf(CANCEL_REASON, str(d, 'reason')));
      if (isRenter) secondary.push({ label: 'Tìm account khác', to: '/listings' });
      break;
    case 'OrderStarted':
      primary = isRenter ? order('#credentials', 'Xem thông tin đăng nhập') : order();
      time('Hết giờ lúc', 'blockEndAt');
      break;
    case 'OrderEndingSoon':
      primary = isRenter ? order('#extend', 'Gia hạn') : order();
      time('Còn lại', 'scheduledEndAt', 'countdown');
      break;
    case 'OrderEnded':
      primary = order();
      time('Kết thúc lúc', 'actualEndAt');
      if (isOwner) secondary.push({ label: 'Xem ví', to: '/wallet' });
      if (isRenter) secondary.push({ label: 'Tiền cọc', to: '/wallet#deposits' });
      break;
    case 'OrderExtended':
      primary = order('#extend');
      money('Tiền gia hạn', 'rentAmount');
      time('Kết thúc mới', 'newScheduledEndAt');
      break;
    case 'OrderExtensionExpired':
    case 'OrderExtensionFailed':
      primary = order('#extend');
      break;
    case 'CredentialViewed':
    case 'HandoverConfirmed':
      primary = order();
      break;
    case 'OtpRequested':
      primary = order('#otp', 'Gửi mã OTP');
      break;
    case 'DepositStateChanged':
      primary = order();
      text('Trạng thái cọc', str(d, 'to') && labelOf(DEPOSIT_STATUS, str(d, 'to')));
      time('Giữ đến', 'holdUntil');
      secondary.push({ label: 'Tiền cọc của tôi', to: '/wallet#deposits' });
      break;
    case 'RefundCompleted':
      primary = { label: 'Xem ví', to: '/wallet#history' };
      money('Số tiền', 'amount');
      if (orderId) secondary.push({ label: 'Xem đơn', to: `/orders/${orderId}` });
      break;
    case 'WalletToppedUp':
      primary = { label: 'Xem ví', to: '/wallet#history' };
      money('Số tiền', 'amount');
      break;
    case 'RefundFailed':
      primary = me.can('withdrawal:list_all', 'ledger:read') ? { label: 'Mở trang tài chính', to: '/finance' } : order();
      text('Lỗi', str(d, 'error'));
      break;

    // ------------------------------------------------------------------ disputes
    case 'DisputeOpened':
    case 'DisputeEvidenceAdded':
    case 'DisputeResolved':
      if (!isRenter && !isOwner && me.can('dispute:read_any')) {
        primary = { label: n.type === 'DisputeOpened' ? 'Xử lý khiếu nại' : 'Xem khiếu nại', to: '/support/disputes' };
      } else {
        primary = order('#disputes', 'Xem khiếu nại');
      }
      if (n.type === 'DisputeOpened') {
        time('Hạn xử lý', 'deadlineAt');
        if (d.urgent === true) text('Mức độ', 'Khẩn cấp — account có thể bị chiếm');
      }
      if (n.type === 'DisputeResolved') {
        money('Khấu trừ cọc', 'depositDeduction');
        money('Hoàn tiền thuê', 'rentRefund');
      }
      break;

    // ------------------------------------------------------------------ help desk chat
    case 'SupportTicketOpened':
    case 'SupportMessagePosted':
    case 'SupportParticipantAdded':
    case 'SupportTicketStatusChanged': {
      const ticketId = str(d, 'ticketId');
      primary = {
        label: n.type === 'SupportTicketOpened' ? 'Trả lời' : 'Mở cuộc trò chuyện',
        to: ticketId ? `/tickets/${ticketId}` : '/tickets',
      };
      text('Chủ đề', str(d, 'subject'));
      break;
    }

    // ------------------------------------------------------------------ money
    case 'SellerHoldReleased':
      primary = { label: 'Rút tiền', to: '/wallet#withdraw' };
      money('Số tiền', 'amount');
      break;
    case 'WithdrawalRequested':
      primary =
        !isOwner && me.can('withdrawal:list_all')
          ? { label: 'Xử lý yêu cầu', to: '/finance' }
          : { label: 'Lịch sử rút tiền', to: '/wallet#withdrawals' };
      money('Số tiền', 'amount');
      break;
    case 'WithdrawalPaid':
    case 'WithdrawalFailed':
      primary = { label: 'Lịch sử rút tiền', to: '/wallet#withdrawals' };
      money('Số tiền', 'amount');
      text('Lý do', str(d, 'reason'));
      break;

    // ------------------------------------------------------------------ listings & game accounts
    case 'ListingStatusChanged':
      primary = { label: 'Xem listing', to: '/my-listings#listings' };
      text('Trạng thái mới', str(d, 'to') && labelOf(LISTING_STATUS, str(d, 'to')));
      text('Ghi chú', str(d, 'note'));
      break;
    case 'ListingSubmittedForReview':
      primary = { label: 'Kiểm duyệt', to: '/admin/listings' };
      break;
    case 'GameAccountPendingVerification':
      primary = { label: 'Xác minh account', to: '/admin/listings#verify-accounts' };
      break;
    case 'GameAccountStateChanged':
      primary = { label: 'Xem account game', to: '/my-listings#game-accounts' };
      text('Trạng thái mới', str(d, 'to') && labelOf(SECURITY_STATE, str(d, 'to')));
      time('Hạn đổi mật khẩu', 'rotationDueAt');
      break;
    case 'RotationOverdue':
      primary = { label: 'Đổi mật khẩu', to: '/my-listings#game-accounts' };
      time('Hạn đổi mật khẩu', 'dueAt');
      break;
    case 'CredentialRotated':
      primary = { label: 'Xem account game', to: '/my-listings#game-accounts' };
      break;

    // ------------------------------------------------------------------ admin
    case 'ApprovalRequested':
    case 'ApprovalDecided':
      primary = { label: n.type === 'ApprovalRequested' ? 'Xem yêu cầu' : 'Xem kết quả', to: '/admin/approvals' };
      text('Loại', str(d, 'actionType') && labelOf(APPROVAL_ACTION, str(d, 'actionType')));
      if (n.type === 'ApprovalDecided') text('Kết quả', str(d, 'status') && labelOf(APPROVAL_STATUS, str(d, 'status')));
      break;
    case 'GameTosStatusChanged':
      primary = me.can('game:read_hidden') ? { label: 'Quản lý game', to: '/admin/games' } : null;
      break;
    case 'UserStatusChanged':
      primary = { label: 'Xem hồ sơ', to: '/profile' };
      text('Trạng thái mới', str(d, 'to') && labelOf(USER_STATUS, str(d, 'to')));
      secondary.push({ label: 'Hướng dẫn', to: '/help' });
      break;

    default:
      if (orderId) primary = order();
  }

  return { primary, secondary, details };
}
