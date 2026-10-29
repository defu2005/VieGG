import { useEffect, useState } from 'react';
import { api } from '../api/client';
import type { LabelDict } from './labels';

export type TicketCategory = 'QUESTION' | 'SUPPORT' | 'COMPLAINT' | 'MEDIATION';
export type TicketStatus = 'OPEN' | 'WAITING_USER' | 'RESOLVED' | 'CLOSED';
export type ParticipantRole = 'REQUESTER' | 'PARTY' | 'STAFF';

export interface TicketParticipant {
  userId: string;
  displayName: string;
  role: ParticipantRole;
  joinedAt: string;
  active: boolean;
}

export interface Ticket {
  id: string;
  subject: string;
  category: TicketCategory;
  status: TicketStatus;
  createdBy: string;
  createdByName: string | null;
  orderId: string | null;
  disputeId: string | null;
  assigneeId: string | null;
  assigneeName: string | null;
  lastMessageAt: string;
  lastMessagePreview: string | null;
  createdAt: string;
  closedAt: string | null;
  unread: number;
  myRole: ParticipantRole | null;
  canManage: boolean;
  participants: TicketParticipant[];
  orderParties: { renterId: string; renterName: string; ownerId: string; ownerName: string } | null;
}

export interface TicketMessage {
  id: string;
  senderId: string | null;
  senderName: string | null;
  senderRole: ParticipantRole | null;
  kind: 'TEXT' | 'SYSTEM';
  body: string;
  attachmentId: string | null;
  attachmentType: string | null;
  createdAt: string;
}

export const TICKET_CATEGORY: LabelDict = {
  QUESTION: { label: 'Hỏi đáp', hint: 'Câu hỏi về cách dùng, phí, quy định' },
  SUPPORT: { label: 'Nhờ hỗ trợ', hint: 'Gặp sự cố cần hỗ trợ viên xử lý' },
  COMPLAINT: { label: 'Khiếu nại', hint: 'Phản ánh về đơn thuê, người dùng khác hoặc dịch vụ' },
  MEDIATION: { label: 'Đàm phán', hint: 'Hỗ trợ viên mời các bên vào để thương lượng, giải quyết tranh chấp' },
};

export const TICKET_STATUS: LabelDict = {
  OPEN: { label: 'Chờ hỗ trợ', hint: 'Đang chờ hỗ trợ viên trả lời', tone: 'warn' },
  WAITING_USER: { label: 'Đã trả lời', hint: 'Hỗ trợ viên đã trả lời, đang chờ bạn phản hồi', tone: 'ok' },
  RESOLVED: { label: 'Đã giải quyết', hint: 'Nhắn tiếp sẽ mở lại yêu cầu', tone: 'ok' },
  CLOSED: { label: 'Đã đóng', hint: 'Không nhắn thêm được; tạo yêu cầu mới nếu cần' },
};

export const PARTICIPANT_ROLE: Record<ParticipantRole, string> = {
  REQUESTER: 'Người yêu cầu',
  PARTY: 'Được mời',
  STAFF: 'Hỗ trợ viên',
};

const EVENT = 'rentgg:helpdesk-changed';

/** Tells the nav badge to refetch (after reading or sending). */
export function notifyHelpdeskChanged() {
  window.dispatchEvent(new Event(EVENT));
}

export interface HelpdeskUnread {
  unreadTickets: number;
  awaitingStaff: number;
}

/** Conversations with unread messages (+ open tickets waiting for staff); refreshes every 30 s. */
export function useHelpdeskUnread(enabled: boolean, pathname: string): HelpdeskUnread {
  const [state, setState] = useState<HelpdeskUnread>({ unreadTickets: 0, awaitingStaff: 0 });
  useEffect(() => {
    if (!enabled) {
      setState({ unreadTickets: 0, awaitingStaff: 0 });
      return;
    }
    let cancelled = false;
    const refresh = () =>
      api<HelpdeskUnread>('/tickets/unread')
        .then((r) => !cancelled && setState(r))
        .catch(() => undefined);
    refresh();
    const timer = setInterval(refresh, 30_000);
    window.addEventListener(EVENT, refresh);
    return () => {
      cancelled = true;
      clearInterval(timer);
      window.removeEventListener(EVENT, refresh);
    };
  }, [enabled, pathname]);
  return state;
}
