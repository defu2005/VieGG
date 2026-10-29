/**
 * Vietnamese labels for every code the backend sends (statuses, modes, ledger codes…).
 * Pages should never print a raw code: use labelOf(DICT, value). Unknown codes fall back to
 * a readable form ("SOME_CODE" → "Some code") instead of the raw enum.
 */

export type Tone = 'ok' | 'warn' | 'bad' | undefined;

export interface LabelDef {
  label: string;
  /** One-sentence explanation, shown as a tooltip and in the glossary on the help page. */
  hint?: string;
  tone?: Tone;
}

export type LabelDict = Record<string, LabelDef>;

export function humanize(code: string): string {
  const text = code
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .replace(/[_:.-]+/g, ' ')
    .trim()
    .toLowerCase();
  return text ? text.charAt(0).toUpperCase() + text.slice(1) : code;
}

export function labelOf(dict: LabelDict, value: string | null | undefined): string {
  if (value == null || value === '') return '—';
  return dict[value]?.label ?? humanize(value);
}

export function hintOf(dict: LabelDict, value: string | null | undefined): string | undefined {
  return value ? dict[value]?.hint : undefined;
}

export function toneOf(dict: LabelDict, value: string | null | undefined): Tone {
  return value ? dict[value]?.tone : undefined;
}

export const ORDER_STATUS: LabelDict = {
  PENDING_PAYMENT: { label: 'Chờ thanh toán', tone: 'warn', hint: 'Đơn đã tạo, bạn cần thanh toán trước khi hết hạn để giữ chỗ.' },
  AWAITING_OWNER_APPROVAL: { label: 'Chờ chủ account duyệt', tone: 'warn', hint: 'Đã thanh toán, đang chờ chủ account đồng ý cho thuê.' },
  CONFIRMED: { label: 'Đã xác nhận', tone: 'ok', hint: 'Đơn đã chốt. Đến giờ bắt đầu bạn xem được thông tin đăng nhập.' },
  IN_RENTAL: { label: 'Đang thuê', tone: 'ok', hint: 'Bạn đang trong khung giờ thuê.' },
  ENDED: { label: 'Đã kết thúc', tone: 'ok', hint: 'Hết giờ thuê. Tiền cọc sẽ được hoàn sau thời gian giữ nếu không có khiếu nại.' },
  CANCELLED: { label: 'Đã hủy', tone: 'bad', hint: 'Đơn đã bị hủy. Tiền được hoàn theo chính sách hủy.' },
  EXPIRED: { label: 'Hết hạn', tone: 'bad', hint: 'Không thanh toán kịp nên đơn tự hủy.' },
  REJECTED: { label: 'Bị từ chối', tone: 'bad', hint: 'Chủ account từ chối hoặc không duyệt kịp. Bạn được hoàn toàn bộ tiền.' },
};

export const LISTING_STATUS: LabelDict = {
  DRAFT: { label: 'Nháp', hint: 'Chưa gửi duyệt, người khác chưa thấy.' },
  PENDING_REVIEW: { label: 'Chờ duyệt', tone: 'warn', hint: 'Đang chờ đội ngũ VieGG kiểm tra.' },
  ACTIVE: { label: 'Đang hiển thị', tone: 'ok', hint: 'Người thuê tìm thấy và đặt được.' },
  PAUSED: { label: 'Tạm dừng', tone: 'warn', hint: 'Bạn đã tạm ẩn, không nhận đơn mới.' },
  REJECTED: { label: 'Bị từ chối', tone: 'bad', hint: 'Chưa đạt yêu cầu. Xem lý do, sửa và gửi duyệt lại.' },
  SUSPENDED: { label: 'Bị đình chỉ', tone: 'bad', hint: 'Bị khóa do vi phạm hoặc cần rà soát bảo mật.' },
};

export const END_MODE: LabelDict = {
  FORCE_AT_END: { label: 'Hết giờ là đổi mật khẩu ngay', hint: 'Người thuê bị đăng xuất đúng giờ kết thúc.' },
  GRACE_FOR_CURRENT_MATCH: { label: 'Cho thêm thời gian kết thúc trận', hint: 'Người thuê được thêm vài phút để đánh nốt trận đang chơi.' },
  STAY_ONLINE_UNTIL_RECLAIM: { label: 'Giữ đăng nhập đến khi chủ lấy lại', hint: 'Account vẫn đăng nhập cho đến khi chủ account bấm "Lấy lại".' },
};

export const HANDOVER_MODE: LabelDict = {
  STATIC_CREDENTIAL: { label: 'Giao tài khoản + mật khẩu', hint: 'Người thuê tự xem tài khoản và mật khẩu trong đơn khi đến giờ.' },
  OWNER_ASSISTED_OTP: { label: 'Chủ account gửi mã OTP', hint: 'Game cần mã OTP khi đăng nhập, chủ account gửi mã qua đơn.' },
};

export const GAME_RISK: LabelDict = {
  LOW: { label: 'Rủi ro thấp', tone: 'ok', hint: 'Hiếm khi xảy ra sự cố khi cho thuê.' },
  MEDIUM: { label: 'Rủi ro trung bình', tone: 'warn', hint: 'Thỉnh thoảng có sự cố như mất vật phẩm hoặc account bị báo cáo.' },
  HIGH: { label: 'Rủi ro cao', tone: 'bad', hint: 'Dễ bị mất vật phẩm, bị đổi thông tin khôi phục hoặc bị nhà phát hành khóa.' },
  VERY_HIGH: { label: 'Rủi ro rất cao', tone: 'bad', hint: 'Nguy cơ mất hẳn account hoặc bị khóa vĩnh viễn là cao.' },
};

export const DEPOSIT_STATUS: LabelDict = {
  PENDING_PAYMENT: { label: 'Chờ thanh toán', tone: 'warn', hint: 'Cọc sẽ được giữ khi bạn thanh toán đơn.' },
  HELD: { label: 'Đang giữ', tone: 'warn', hint: 'VieGG đang giữ cọc trong lúc thuê.' },
  POST_RENTAL_HOLD: { label: 'Giữ sau khi thuê', tone: 'warn', hint: 'Giữ thêm một thời gian ngắn để chủ account kiểm tra.' },
  DISPUTED: { label: 'Đang khiếu nại', tone: 'bad', hint: 'Đang có khiếu nại, cọc tạm giữ đến khi có kết quả.' },
  REFUND_PENDING: { label: 'Đang hoàn', tone: 'warn', hint: 'Đã duyệt hoàn, tiền đang chuyển về cho bạn.' },
  REFUNDED: { label: 'Đã hoàn', tone: 'ok', hint: 'Tiền cọc đã về tài khoản của bạn.' },
  SETTLED: { label: 'Đã tất toán', tone: 'ok', hint: 'Cọc đã xử lý xong (hoàn và/hoặc khấu trừ).' },
  VOID: { label: 'Đã hủy', hint: 'Đơn không thành nên không phát sinh cọc.' },
};

export const DISPUTE_STATUS: LabelDict = {
  OPEN: { label: 'Đang xử lý', tone: 'warn' },
  RESOLVED: { label: 'Đã chấp nhận', tone: 'ok' },
  REJECTED: { label: 'Bị bác', tone: 'bad' },
};

export const DISPUTE_TYPE: LabelDict = {
  OWNER_DAMAGE: { label: 'Chủ account báo thiệt hại' },
  ACCOUNT_TAKEOVER: { label: 'Báo bị chiếm account' },
  RENTER_LOGIN_FAILED: { label: 'Không đăng nhập được' },
  RENTER_NOT_AS_DESCRIBED: { label: 'Account không đúng mô tả' },
};

export const WITHDRAWAL_STATUS: LabelDict = {
  REQUESTED: { label: 'Chờ chuyển khoản', tone: 'warn', hint: 'Bộ phận tài chính sẽ chuyển khoản trong giờ làm việc.' },
  PAID: { label: 'Đã chuyển', tone: 'ok', hint: 'Tiền đã được chuyển vào tài khoản ngân hàng.' },
  FAILED: { label: 'Thất bại', tone: 'bad', hint: 'Chuyển khoản lỗi, tiền đã được trả lại vào ví.' },
};

/** Wallet statement line types (ledger transaction types touching the user's wallet). */
export const WALLET_TX_TYPE: Record<string, string> = {
  WALLET_TOPUP: 'Nạp tiền',
  WALLET_PAYMENT: 'Thanh toán đơn thuê',
  REFUND_COMPLETED: 'Hoàn tiền vào ví',
  SELLER_HOLD_RELEASED: 'Doanh thu cho thuê',
  SELLER_DISPUTE_HOLD_RELEASED: 'Doanh thu sau khiếu nại',
  DEPOSIT_DEDUCTED: 'Nhận tiền khấu trừ cọc',
  ORDER_CANCELLED_REVENUE: 'Tiền phạt hủy đơn',
  WITHDRAWAL_REQUESTED: 'Rút tiền về ngân hàng',
  WITHDRAWAL_FAILED: 'Trả lại tiền rút thất bại',
  LEDGER_ADJUSTMENT: 'Điều chỉnh',
};

export const USER_STATUS: LabelDict = {
  ACTIVE: { label: 'Hoạt động bình thường', tone: 'ok' },
  RESTRICTED: { label: 'Bị hạn chế', tone: 'warn', hint: 'Một số chức năng bị tạm khóa.' },
  SUSPENDED: { label: 'Bị khóa', tone: 'bad', hint: 'Không thể sử dụng hệ thống.' },
};

export const RISK_TIER: LabelDict = {
  STANDARD: { label: 'Bình thường', tone: 'ok' },
  HIGH: { label: 'Rủi ro cao', tone: 'bad', hint: 'Doanh thu bị giữ lâu hơn trước khi được rút.' },
};

export const KYC_STATUS: LabelDict = {
  NONE: { label: 'Chưa xác minh' },
  PENDING: { label: 'Chờ duyệt', tone: 'warn' },
  VERIFIED: { label: 'Đã xác minh', tone: 'ok' },
  REJECTED: { label: 'Bị từ chối', tone: 'bad' },
};

export const TOS_STATUS: LabelDict = {
  ALLOWED: { label: 'Được phép cho thuê', tone: 'ok' },
  REVIEW_REQUIRED: { label: 'Chờ thẩm định điều khoản', tone: 'warn' },
  PROHIBITED: { label: 'Bị cấm', tone: 'bad' },
  SUSPENDED: { label: 'Tạm ngưng', tone: 'bad' },
};

export const APPROVAL_STATUS: LabelDict = {
  PENDING: { label: 'Chờ duyệt', tone: 'warn' },
  APPROVED: { label: 'Đã duyệt', tone: 'ok' },
  EXECUTED: { label: 'Đã thực hiện', tone: 'ok' },
  REJECTED: { label: 'Bị từ chối', tone: 'bad' },
};

export const APPROVAL_ACTION: LabelDict = {
  GAME_ALLOW: { label: 'Mở lại game cho thuê' },
  ACCOUNT_SECURITY_RESTORE: { label: 'Mở khóa account game bị khóa bảo mật' },
  SELLER_HOLD_EARLY_RELEASE: { label: 'Cho chủ account nhận doanh thu sớm' },
  LEDGER_ADJUSTMENT: { label: 'Điều chỉnh sổ cái' },
};

export const SECURITY_STATE: LabelDict = {
  PENDING_VERIFICATION: { label: 'Chờ xác minh', tone: 'warn', hint: 'Đội ngũ VieGG sẽ kiểm tra thông tin khôi phục.' },
  AVAILABLE: { label: 'Sẵn sàng', tone: 'ok' },
  IN_USE: { label: 'Đang được thuê', tone: 'ok' },
  AWAITING_RECLAIM: { label: 'Chờ lấy lại', tone: 'warn', hint: 'Người thuê vẫn đang đăng nhập. Bấm “Lấy lại account” rồi đổi mật khẩu.' },
  ROTATION_PENDING: { label: 'Cần đổi mật khẩu', tone: 'bad', hint: 'Lượt thuê vừa kết thúc, hãy đổi mật khẩu để bảo vệ account.' },
  ROTATION_OVERDUE: { label: 'Quá hạn đổi mật khẩu', tone: 'bad', hint: 'Listing của account đang bị ẩn. Đổi mật khẩu để hiển thị lại.' },
  SUSPENDED_SECURITY_REVIEW: { label: 'Tạm khóa để rà soát', tone: 'bad' },
};

export const CANCEL_REASON: LabelDict = {
  RENTER_CANCELLED: { label: 'Người thuê hủy' },
  RENTER_LATE_CANCELLED: { label: 'Người thuê hủy sát giờ' },
  OWNER_CANCELLED: { label: 'Chủ account hủy' },
  OWNER_FAULT_NOT_READY: { label: 'Account chưa sẵn sàng (lỗi chủ account)' },
  PLATFORM_GAME_SUSPENDED: { label: 'Game bị tạm ngưng trên VieGG' },
  LATE_PAYMENT: { label: 'Thanh toán quá hạn' },
  OWNER_REJECTED: { label: 'Chủ account từ chối' },
  APPROVAL_TIMEOUT: { label: 'Chủ account không duyệt kịp' },
};

export const ROLE: LabelDict = {
  USER: { label: 'Người dùng' },
  SUPPORT: { label: 'Hỗ trợ khách hàng' },
  ADMIN: { label: 'Quản trị viên' },
  FINANCE: { label: 'Tài chính' },
};

export const TX_TYPE: LabelDict = {
  PAYMENT_CAPTURED: { label: 'Nhận thanh toán' },
  ORDER_ENDED: { label: 'Kết thúc đơn' },
  SELLER_HOLD_RELEASED: { label: 'Nhả doanh thu cho chủ account' },
  SELLER_DISPUTE_HOLD: { label: 'Giữ doanh thu do khiếu nại' },
  SELLER_DISPUTE_HOLD_RELEASED: { label: 'Nhả doanh thu sau khiếu nại' },
  DEPOSIT_REFUND_REQUESTED: { label: 'Yêu cầu hoàn cọc' },
  REFUND_COMPLETED: { label: 'Hoàn tiền xong' },
  DEPOSIT_DEDUCTED: { label: 'Khấu trừ cọc' },
  RENT_REFUND: { label: 'Hoàn tiền thuê' },
  ORDER_CANCELLED_REFUND: { label: 'Hoàn tiền do hủy đơn' },
  ORDER_CANCELLED_REVENUE: { label: 'Phí hủy đơn' },
  WITHDRAWAL_REQUESTED: { label: 'Yêu cầu rút tiền' },
  WITHDRAWAL_PAID: { label: 'Đã chi rút tiền' },
  WITHDRAWAL_FAILED: { label: 'Rút tiền thất bại' },
  LEDGER_ADJUSTMENT: { label: 'Điều chỉnh thủ công' },
};

export const ACCOUNT_CODE: LabelDict = {
  PSP_CLEARING: { label: 'Cổng thanh toán (chờ đối soát)' },
  RENT_ESCROW: { label: 'Tiền thuê đang giữ' },
  DEPOSIT_HELD: { label: 'Tiền cọc đang giữ' },
  REFUND_PAYABLE: { label: 'Tiền phải hoàn' },
  PLATFORM_REVENUE: { label: 'Doanh thu VieGG' },
  SELLER_REVIEW_HOLD: { label: 'Doanh thu chủ account (đang giữ)' },
  SELLER_DISPUTE_HOLD: { label: 'Doanh thu chủ account (giữ do khiếu nại)' },
  SELLER_AVAILABLE: { label: 'Số dư chủ account (rút được)' },
  SELLER_PAYOUT_PROCESSING: { label: 'Đang chuyển khoản cho chủ account' },
};

/** Field names that can appear in approval payloads or reports. */
export const FIELD: LabelDict = {
  gameId: { label: 'Game' },
  gameAccountId: { label: 'Account game' },
  orderId: { label: 'Đơn thuê' },
  ownerUserId: { label: 'Chủ account' },
  userId: { label: 'Người dùng' },
  tosStatus: { label: 'Trạng thái điều khoản' },
  tosUrl: { label: 'Link điều khoản' },
  reviewNote: { label: 'Ghi chú thẩm định' },
  note: { label: 'Ghi chú' },
  postings: { label: 'Các dòng bút toán' },
  accountCode: { label: 'Tài khoản' },
  amount: { label: 'Số tiền' },
  status: { label: 'Trạng thái' },
  escrow: { label: 'Tiền còn giữ' },
  deducted_amount: { label: 'Đã khấu trừ' },
  refunded_amount: { label: 'Đã hoàn' },
  ledger_held: { label: 'Sổ cái đang giữ' },
  order_id: { label: 'Đơn thuê' },
};

/** Preset reasons for staff actions. Codes are stored; staff pick a sentence instead of inventing a code. */
export const REASON: LabelDict = {
  SECURITY_REVIEW: { label: 'Cần rà soát bảo mật' },
  SUSPECTED_FRAUD: { label: 'Nghi ngờ gian lận' },
  STOLEN_ACCOUNT_REPORT: { label: 'Có báo cáo account bị đánh cắp' },
  TOS_VIOLATION: { label: 'Vi phạm điều khoản game' },
  MISLEADING_LISTING: { label: 'Mô tả sai sự thật' },
  ABUSIVE_BEHAVIOR: { label: 'Hành vi lạm dụng / quấy rối' },
  CHARGEBACK: { label: 'Khiếu nại hoàn tiền với ngân hàng' },
  EVIDENCE_CONFIRMED: { label: 'Bằng chứng xác nhận khiếu nại' },
  INSUFFICIENT_EVIDENCE: { label: 'Không đủ bằng chứng' },
  OWNER_FAULT: { label: 'Lỗi thuộc về chủ account' },
  RENTER_FAULT: { label: 'Lỗi thuộc về người thuê' },
  ISSUE_RESOLVED: { label: 'Sự cố đã được khắc phục' },
  OWNER_VERIFIED: { label: 'Đã xác minh chính chủ' },
  CORRECTION: { label: 'Sửa sai sót số liệu' },
  GOODWILL: { label: 'Hỗ trợ thiện chí cho khách hàng' },
  FALSE_CLAIM: { label: 'Khiếu nại sai sự thật' },
  RISKS_CONFIRMED: { label: 'Có rủi ro được chấp nhận (tiền tính tự động)' },
  RISKS_REJECTED: { label: 'Mọi rủi ro đều bị bác' },
  RISK_CLEARED: { label: 'Đã hết rủi ro' },
  TOS_UPDATED: { label: 'Nhà phát hành đã cập nhật điều khoản' },
  OWNER_ROTATED_PASSWORD: { label: 'Chủ account đã đổi mật khẩu' },
  TRUSTED_SELLER: { label: 'Chủ account uy tín' },
  LOGIN_FROM_UNKNOWN_DEVICE: { label: 'Đăng nhập từ thiết bị lạ' },
  DEV_SEED: { label: 'Dữ liệu mẫu' },
};
