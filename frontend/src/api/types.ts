// Mirrors the backend read models. Keep in sync with the Java records under src/main/java/com/rentgg.

export interface TokenResponse {
  accessToken: string;
  accessTokenExpiresAt: string;
  refreshToken: string;
  refreshTokenExpiresAt: string;
  tokenType: 'Bearer';
}

export interface Me {
  id: string;
  email: string;
  displayName: string;
  dateOfBirth: string;
  roles: string[];
  /** Permission codes such as 'listing:approve'. Gate UI on these, not on role names. */
  permissions: string[];
  /** Staff account (platform operator): back office only, no customer features (renting, listing, wallet). */
  staff: boolean;
  status: string;
  kycStatus: string;
  riskTier: string;
}

export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export type ListingStatus = 'DRAFT' | 'PENDING_REVIEW' | 'ACTIVE' | 'PAUSED' | 'REJECTED' | 'SUSPENDED';
export type EndMode = 'FORCE_AT_END' | 'GRACE_FOR_CURRENT_MATCH' | 'STAY_ONLINE_UNTIL_RECLAIM';

export interface Listing {
  id: string;
  gameAccountId: string;
  ownerId: string;
  gameId: string;
  title: string;
  description: string | null;
  attributes: Record<string, unknown> | null;
  /** For listings with packages: the shortest package's price per hour (search/sort only). */
  pricePerHour: number;
  /** Rental packages sorted by duration. Empty = priced per hour (rent = pricePerHour × duration). */
  pricingTiers: PricingTier[];
  /** When the game has owner risks: the sum of the owner's deposit for each of them. */
  depositAmount: number;
  /** Owner's amount per game risk: deposit (OWNER risk) or compensation (RENTER risk, absent = refund only). */
  riskTerms: RiskTerm[];
  autoAccept: boolean;
  endMode: EndMode;
  /** Owner's free-cancel window (minutes before the start); null = the game's default. */
  renterFreeCancelMinutes?: number | null;
  status: ListingStatus;
  reviewNote: string | null;
  version: number;
  createdAt: string;
  /** Risks the owner added to this listing on top of the game's (ownerDefined); riskTerms hold their amounts too. */
  ownerRisks?: GameRisk[];
  /** Account screenshots in display order; bytes at /listings/{id}/images/{imageId}. */
  imageIds: string[];
}

export interface PricingTier {
  durationMinutes: number;
  price: number;
}

/** OWNER: the renter breaks the account (covered by a deposit). RENTER: the renter cannot use what they paid for. */
export type RiskSide = 'OWNER' | 'RENTER';

/** A risk of a game, defined by staff, or one an owner added to their own listing (ownerDefined). */
export interface GameRisk {
  id: string;
  side: RiskSide;
  name: string;
  description: string | null;
  /** Staff's free-text advice on the deposit (OWNER) or compensation (RENTER); null = none. */
  recommendation: string | null;
  /** Complaints about it close this many minutes after the first credential view (RENTER) / the rental end (OWNER). */
  complaintWindowMinutes: number | null;
  /**
   * OWNER risks: true = stackable (rank drop, reputation drop… deductions add up); false = exclusive (permanent ban…:
   * the renter pays only this deposit). Order deposit = max(sum of stackable, largest exclusive).
   */
  stackable: boolean;
  /** Added by the listing's owner for that listing only (absent on older servers = staff risk). */
  ownerDefined?: boolean;
}

/** A risk the owner adds to their listing (body ownerRisks of POST / PUT /listings); id null = new. */
export interface OwnerRiskInput {
  id: string | null;
  side: RiskSide;
  name: string;
  description: string | null;
  complaintWindowMinutes: number | null;
  stackable: boolean;
  /** Deposit (OWNER) or compensation (RENTER, null = refund only). */
  amount: number | null;
}

/** Body of PUT /admin/games/{id}/risks; id null = new risk. */
export interface GameRiskInput {
  id: string | null;
  side: RiskSide;
  name: string;
  description: string | null;
  recommendation: string | null;
  complaintWindowMinutes: number | null;
  stackable: boolean;
}

export interface RiskTerm {
  riskId: string;
  amount: number | null;
}

/** One risk as frozen in an order's policySnapshot.risks. */
export interface OrderRisk {
  riskId: string;
  side: RiskSide;
  name: string;
  description: string | null;
  /** OWNER: deposit share (null = whole deposit, old listing). RENTER: compensation (null = refund only). */
  amount: number | null;
  complaintWindowMinutes: number | null;
  /** Absent on orders booked before stackable/exclusive risks existed (= stackable). */
  stackable?: boolean;
  /** false: the owner chose no deposit for this OWNER risk, it cannot be claimed. Absent on older orders (= covered). */
  covered?: boolean;
}

export interface Policy {
  handoverMode: string;
  depositHoldHours: number;
  complaintWindowMinutes: number;
  minDurationMinutes: number;
  maxDurationMinutes: number;
  graceMinutes: number;
  renterFreeCancelMinutes: number;
  lateCancelRefundBps: number;
  minAge: number;
}

export interface BusyPeriod {
  startAt: string;
  endAt: string;
  /** BOOKED: an order; OWNER_BUSY: time the owner marked as busy. */
  kind?: 'BOOKED' | 'OWNER_BUSY';
}

/** Busy time the owner set on their account (owner-only view, with the private note). */
export interface BusyBlock {
  id: string;
  startAt: string;
  endAt: string;
  note: string | null;
}

export interface ListingDetail {
  listing: Listing;
  policy: Policy;
  busyPeriods: BusyPeriod[];
  bookable: boolean;
  /** The game's current risks; listing.riskTerms has the owner's amount for each. */
  risks: GameRisk[];
}

export type OrderStatus =
  | 'PENDING_PAYMENT'
  | 'AWAITING_OWNER_APPROVAL'
  | 'CONFIRMED'
  | 'IN_RENTAL'
  | 'ENDED'
  | 'CANCELLED'
  | 'EXPIRED'
  | 'REJECTED';

export interface Order {
  id: string;
  listingId: string;
  gameAccountId: string;
  gameId: string;
  renterId: string;
  ownerId: string;
  status: OrderStatus;
  startAt: string;
  scheduledEndAt: string;
  blockEndAt: string;
  actualEndAt: string | null;
  rentAmount: number;
  platformFee: number;
  sellerNet: number;
  depositAmount: number;
  amountToPay: number;
  endMode: EndMode;
  paymentExpiresAt: string | null;
  approvalExpiresAt: string | null;
  credentialFirstViewedAt: string | null;
  complaintWindowEndsAt: string | null;
  handoverConfirmedAt: string | null;
  cancelReason: string | null;
  /** Minutes and rent added by paid extensions (already included in scheduledEndAt / rentAmount). */
  extendedMinutes: number;
  extensionRentAmount: number;
  /** Terms frozen at booking (durationMinutes, endMode, …). */
  policySnapshot?: Record<string, unknown>;
  createdAt: string;
  version: number;
}

export type ExtensionStatus = 'PENDING_PAYMENT' | 'PAID' | 'EXPIRED' | 'CANCELLED';

export interface OrderExtension {
  id: string;
  orderId: string;
  durationMinutes: number;
  rentAmount: number;
  status: ExtensionStatus;
  newScheduledEndAt: string;
  newBlockEndAt: string;
  paymentExpiresAt: string;
  paymentIntentId: string | null;
  paidAt: string | null;
  createdAt: string;
}

export interface ExtensionOption {
  durationMinutes: number;
  price: number;
  newEndAt: string;
  available: boolean;
  unavailableReason: string | null;
}

export interface ExtensionOptions {
  allowed: boolean;
  blockedCode: string | null;
  blockedReason: string | null;
  scheduledEndAt: string;
  /** Start of the next booking of the account, which limits how long the rental can be extended. */
  nextBookingAt: string | null;
  remainingMinutes: number;
  options: ExtensionOption[];
  pending: OrderExtension | null;
  history: OrderExtension[];
}

export interface ExtensionCheckout {
  extension: OrderExtension;
  intentId: string;
  amount: number;
  status: string;
  checkoutUrl: string | null;
}

export interface Deposit {
  id: string;
  orderId: string;
  amount: number;
  status: string;
  holdUntil: string | null;
  deductedAmount: number;
  refundedAmount: number;
}

export interface OrderDetail {
  order: Order;
  deposit: Deposit | null;
  refunds: RefundItem[];
}

/** Refund of an order; always credited to the renter's wallet. */
export interface RefundItem {
  id: string;
  kind: 'RENT' | 'DEPOSIT' | string;
  amount: number;
  status: 'PENDING' | 'SUCCEEDED' | 'FAILED' | string;
  reason: string;
  attempts: number;
}

export interface PayResponse {
  intentId: string;
  amount: number;
  status: string;
  checkoutUrl: string | null;
}

export interface Credentials {
  username: string;
  password: string;
  credentialVersion: number;
  complaintWindowEndsAt: string;
  availableUntil: string;
  otpNote: string | null;
}

export type TosStatus = 'ALLOWED' | 'REVIEW_REQUIRED' | 'PROHIBITED' | 'SUSPENDED';
export type HandoverMode = 'STATIC_CREDENTIAL' | 'OWNER_ASSISTED_OTP';

/** Editable per-game parameters (GamePolicy on the backend, System Design §14.1). */
export interface GamePolicy {
  name: string;
  publisher: string | null;
  tosUrl: string | null;
  handoverMode: HandoverMode;
  passwordChangeKicksSessions: boolean;
  depositMin: number;
  depositMax: number;
  depositHoldHours: number;
  sellerHoldHours: number;
  sellerHoldHoursHighRisk: number;
  complaintWindowMinutes: number;
  minDurationMinutes: number;
  maxDurationMinutes: number;
  graceMinutes: number;
  platformFeeBps: number;
  renterFreeCancelMinutes: number;
  lateCancelRefundBps: number;
  ownerFreeCancelHours: number;
  minAge: number;
  riskNotes: string | null;
  riskLevel: GameRiskLevel;
  /** What makes one game account unique; null in an update keeps the current rule. */
  accountKey: AccountKeyRule | null;
}

export type GameRiskLevel = 'LOW' | 'MEDIUM' | 'HIGH' | 'VERY_HIGH';

/** LOGIN = login username (old behaviour); the others ask owners for an in-game identifier. */
export type AccountKeyType = 'LOGIN' | 'UUID' | 'NUMERIC_ID' | 'NAME';

export interface AccountKeyRule {
  type: AccountKeyType;
  /** Shown to owners, e.g. "UID", "Riot ID". Null = default label of the type. */
  label: string | null;
  example: string | null;
  /** The same identifier may exist once per server region. */
  perServer: boolean;
}

export interface GameView extends GamePolicy {
  id: string;
  code: string;
  tosStatus: TosStatus;
  tosReviewedAt: string | null;
  accountKey: AccountKeyRule;
  /** Label to show for the identifier (custom label or the type's default). */
  accountKeyLabel: string;
  /** Active risks in display order. */
  risks: GameRisk[];
  /** Rental packages staff suggest; owners pick which ones a listing offers. */
  suggestedPackages?: SuggestedPackage[];
}

/** A rental package suggested by staff. {@code price} null = only the duration is suggested. */
export interface SuggestedPackage {
  durationMinutes: number;
  price: number | null;
}

export type UserStatus = 'ACTIVE' | 'RESTRICTED' | 'SUSPENDED';
export type RiskTier = 'STANDARD' | 'HIGH';

export type DisputeType =
  | 'OWNER_DAMAGE'
  | 'ACCOUNT_TAKEOVER'
  | 'RENTER_LOGIN_FAILED'
  | 'RENTER_NOT_AS_DESCRIBED'
  | 'OWNER_RISK'
  | 'RENTER_RISK';
export type DisputeStatus = 'OPEN' | 'RESOLVED' | 'REJECTED';

export interface Dispute {
  id: string;
  orderId: string;
  openedBy: string;
  type: DisputeType;
  status: DisputeStatus;
  description: string;
  evidence: Record<string, unknown>[];
  deadlineAt: string | null;
  depositDeduction: number;
  rentRefund: number;
  decidedBy: string | null;
  decidedAt: string | null;
  reasonCode: string | null;
  decisionNote: string | null;
  createdAt: string;
  /** OWNER_RISK / single-risk RENTER_RISK: the risk of the order snapshot (null when several risks). */
  riskId: string | null;
  /** RENTER_RISK: owner → renter compensation paid. */
  compensation: number;
  /** RENTER_RISK: every risk of the complaint, decided one by one by support. Empty for other types. */
  items?: DisputeItem[];
}

export type DisputeItemStatus = 'OPEN' | 'ACCEPTED' | 'REJECTED';

export interface DisputeItem {
  id: string;
  riskId: string;
  status: DisputeItemStatus;
  /** ACCEPTED: the owner's compensation for this risk (0 = refund only). */
  compensation: number;
  reasonCode: string | null;
  note: string | null;
  decidedBy: string | null;
  decidedAt: string | null;
}

export interface Withdrawal {
  id: string;
  ownerId: string;
  amount: number;
  status: string;
  bank: Record<string, string>;
  bankRef: string | null;
  failureReason: string | null;
  processedAt: string | null;
  createdAt: string;
}

export type ApprovalActionType = 'GAME_ALLOW' | 'ACCOUNT_SECURITY_RESTORE' | 'SELLER_HOLD_EARLY_RELEASE' | 'LEDGER_ADJUSTMENT';
export type ApprovalStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'EXECUTED';

export interface Approval {
  id: string;
  actionType: ApprovalActionType;
  payload: Record<string, unknown>;
  requestedBy: string;
  reasonCode: string;
  status: ApprovalStatus;
  approvedBy: string | null;
  decidedAt: string | null;
  executedAt: string | null;
  expiresAt: string | null;
  resultNote: string | null;
  createdAt: string;
}

export interface AbuseSignal {
  type: string;
  orderId: string | null;
  createdAt: string;
}

export interface LedgerTx {
  id: string;
  type: string;
  idempotencyKey: string;
  createdAt: string;
  postings: { account: string; ownerUserId: string | null; amount: number }[];
}

export type GameAccountSecurityState =
  | 'PENDING_VERIFICATION'
  | 'AVAILABLE'
  | 'IN_USE'
  | 'AWAITING_RECLAIM'
  | 'ROTATION_PENDING'
  | 'ROTATION_OVERDUE'
  | 'SUSPENDED_SECURITY_REVIEW';

export interface GameAccount {
  id: string;
  ownerId: string;
  gameId: string;
  serverRegion: string | null;
  loginHint: string | null;
  securityState: GameAccountSecurityState;
  securityStateChangedAt?: string | null;
  rotationDueAt: string | null;
  recoveryEmailOwned: boolean;
  recoveryPhoneOwned: boolean;
  twoFactorOwned: boolean;
  recoveryVerifiedAt: string | null;
  activeCredentialVersion: number | null;
  /** In-game identifier as typed by the owner; null for login-identified games or old accounts still to fill in. */
  accountKey: string | null;
}

export interface Balances {
  reviewHold: number;
  disputeHold: number;
  /** Wallet balance: top-ups, refunds and released revenue; pays orders and withdrawals. */
  available: number;
  payoutProcessing: number;
  currency: string;
}

/** A payment provider offered for wallet top-ups (GET /payments/providers, default first). */
export interface PaymentProvider {
  code: 'FAKE' | 'VNPAY' | 'MOMO' | 'ZALOPAY' | 'PAYOS' | string;
  name: string;
  sandbox: boolean;
}

export interface WalletTopup {
  id: string;
  amount: number;
  provider: string;
  status: 'CREATED' | 'SUCCEEDED' | 'FAILED' | 'EXPIRED' | string;
  checkoutUrl: string | null;
  createdAt: string;
}

/** One wallet statement line: amount > 0 money in, < 0 money out. */
export interface WalletEntry {
  transactionId: string;
  type: string;
  orderId: string | null;
  reference: string | null;
  amount: number;
  createdAt: string;
}

export interface NotificationItem {
  id: string;
  type: string;
  title: string;
  body: string;
  data: Record<string, unknown> | null;
  readAt: string | null;
  createdAt: string;
}

export interface PermissionDef {
  code: string;
  resource: string;
  action: string;
  description: string;
}

export interface RoleDef {
  code: string;
  name: string;
  description: string | null;
  system: boolean;
  /** Holders are staff accounts and lose the customer permissions of USER. */
  staff: boolean;
  permissions: string[];
  userCount: number;
}

/** Order row from the finance order lookup (`GET /admin/ledger/orders`). */
export interface LedgerOrderSummary {
  id: string;
  status: string;
  createdAt: string;
  startAt: string;
  rentAmount: number;
  depositAmount: number;
  renterEmail: string;
  ownerEmail: string;
  listingTitle: string;
  gameName: string;
  transactionCount: number;
}

/** Why a staff user search returned this user. */
export type UserMatch = 'USER_ID' | 'EMAIL' | 'NAME' | 'ORDER' | 'LISTING' | 'WITHDRAWAL';

/** GET /staff/users: one row of the staff user search. */
export interface UserHit {
  id: string;
  email: string;
  displayName: string;
  status: string;
  kycStatus: string;
  riskTier: string;
  roles: string[];
  createdAt: string;
  matchedBy: UserMatch[];
}

/** GET /staff/users/brief: who an id is. */
export interface UserBrief {
  id: string;
  email: string;
  displayName: string;
  status: string;
}

/** Admin view of one runtime setting (GET /admin/settings). Values are always strings. */
export interface SettingDef {
  key: string;
  group: 'WITHDRAWAL' | 'SITE' | 'HOME' | 'BANNER' | string;
  groupLabel: string;
  type: 'MONEY' | 'INTEGER' | 'BOOLEAN' | 'TEXT' | 'LONG_TEXT' | 'URL' | 'CHOICE';
  label: string;
  description?: string;
  value: string;
  defaultValue: string;
  customized: boolean;
  publicValue: boolean;
  min?: number;
  max?: number;
  choices?: string[];
  updatedAt?: string;
  updatedBy?: string;
}
