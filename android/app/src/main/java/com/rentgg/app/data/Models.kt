package com.rentgg.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// Phản chiếu read model của backend (xem frontend/src/api/types.ts). Tiền là VND, số nguyên.

@Serializable
data class TokenResponse(
    val accessToken: String,
    val refreshToken: String,
)

@Serializable
data class Me(
    val id: String,
    val email: String,
    val displayName: String,
    val roles: List<String> = emptyList(),
    val status: String = "",
    val kycStatus: String = "",
    val riskTier: String = "",
)

@Serializable
data class PageResponse<T>(
    val content: List<T> = emptyList(),
    val page: Int = 0,
    val size: Int = 0,
    val totalElements: Long = 0,
    val totalPages: Int = 0,
)

@Serializable
data class GameView(
    val id: String,
    val code: String,
    val name: String,
    val publisher: String? = null,
    val tosStatus: String = "ALLOWED",
    val handoverMode: String = "STATIC_CREDENTIAL",
    val depositMin: Long = 0,
    val depositMax: Long = 0,
    val minDurationMinutes: Int = 60,
    val maxDurationMinutes: Int = 1440,
    // What makes one game account unique; type LOGIN = no extra field on the form.
    val accountKey: AccountKeyRule = AccountKeyRule(),
    val accountKeyLabel: String = "",
)

@Serializable
data class AccountKeyRule(
    val type: String = "LOGIN",
    val label: String? = null,
    val example: String? = null,
    val perServer: Boolean = false,
) {
    val needsKey: Boolean get() = type != "LOGIN"
}

@Serializable
data class Listing(
    val id: String,
    val gameAccountId: String,
    val ownerId: String,
    val gameId: String,
    val title: String,
    val description: String? = null,
    val attributes: JsonObject? = null,
    val pricePerHour: Long,
    val depositAmount: Long,
    val autoAccept: Boolean = false,
    val endMode: String = "FORCE_AT_END",
    val status: String = "ACTIVE",
    val reviewNote: String? = null,
    val version: Long = 0,
)

@Serializable
data class Policy(
    val handoverMode: String = "STATIC_CREDENTIAL",
    val depositHoldHours: Int = 0,
    val complaintWindowMinutes: Int = 0,
    val minDurationMinutes: Int = 60,
    val maxDurationMinutes: Int = 1440,
    val graceMinutes: Int = 0,
    val renterFreeCancelMinutes: Int = 0,
    val lateCancelRefundBps: Int = 0,
    val minAge: Int = 18,
)

@Serializable
data class BusyPeriod(val startAt: String, val endAt: String)

@Serializable
data class ListingDetail(
    val listing: Listing,
    val policy: Policy,
    val busyPeriods: List<BusyPeriod> = emptyList(),
    val bookable: Boolean = false,
)

@Serializable
data class Order(
    val id: String,
    val listingId: String,
    val gameId: String,
    val renterId: String,
    val ownerId: String,
    val status: String,
    val startAt: String,
    val scheduledEndAt: String,
    val actualEndAt: String? = null,
    val rentAmount: Long = 0,
    val platformFee: Long = 0,
    val sellerNet: Long = 0,
    val depositAmount: Long = 0,
    val amountToPay: Long = 0,
    val paymentExpiresAt: String? = null,
    val approvalExpiresAt: String? = null,
    val credentialFirstViewedAt: String? = null,
    val complaintWindowEndsAt: String? = null,
    val handoverConfirmedAt: String? = null,
    val cancelReason: String? = null,
    val createdAt: String = "",
)

@Serializable
data class Deposit(
    val id: String,
    val orderId: String,
    val amount: Long,
    val status: String,
    val holdUntil: String? = null,
    val deductedAmount: Long = 0,
    val refundedAmount: Long = 0,
)

@Serializable
data class OrderDetail(val order: Order, val deposit: Deposit? = null)

@Serializable
data class PayResponse(
    val intentId: String,
    val amount: Long,
    val status: String,
    val checkoutUrl: String? = null,
)

@Serializable
data class Credentials(
    val username: String,
    val password: String,
    val complaintWindowEndsAt: String? = null,
    val availableUntil: String? = null,
    val otpNote: String? = null,
)

@Serializable
data class Balances(
    val reviewHold: Long = 0,
    val disputeHold: Long = 0,
    val available: Long = 0,
    val payoutProcessing: Long = 0,
)

@Serializable
data class Withdrawal(
    val id: String,
    val amount: Long,
    val status: String,
    val failureReason: String? = null,
    val createdAt: String = "",
)

@Serializable
data class NotificationItem(
    val id: String,
    val type: String = "",
    val title: String,
    val body: String = "",
    val data: JsonObject? = null,
    val readAt: String? = null,
    val createdAt: String = "",
)

@Serializable
data class GameAccount(
    val id: String,
    val gameId: String,
    val serverRegion: String? = null,
    val securityState: String = "",
    val recoveryEmailOwned: Boolean = false,
    val recoveryPhoneOwned: Boolean = false,
    val twoFactorOwned: Boolean = false,
    val accountKey: String? = null,
)

@Serializable
data class Dispute(
    val id: String,
    val type: String,
    val status: String,
    val description: String = "",
    val depositDeduction: Long = 0,
    val rentRefund: Long = 0,
    val decisionNote: String? = null,
    val createdAt: String = "",
)

// ---- Request bodies ----

@Serializable data class LoginRequest(val email: String, val password: String)

@Serializable
data class RegisterRequest(
    val email: String,
    val password: String,
    val displayName: String,
    val dateOfBirth: String,
)

@Serializable data class RefreshRequest(val refreshToken: String)

@Serializable
data class CreateOrderRequest(val listingId: String, val startAt: String, val durationMinutes: Int)

@Serializable
data class WithdrawRequest(
    val amount: Long,
    val bankName: String,
    val bankAccountNumber: String,
    val bankAccountName: String,
)

@Serializable data class OtpNoteRequest(val note: String)

@Serializable
data class OpenDisputeRequest(val type: String, val description: String, val evidenceUrls: List<String> = emptyList())

@Serializable
data class CreateGameAccountRequest(
    val gameId: String,
    val serverRegion: String?,
    val username: String,
    val password: String,
    val recoveryEmailOwned: Boolean,
    val recoveryPhoneOwned: Boolean,
    val twoFactorOwned: Boolean,
    val accountKey: String? = null,
)

@Serializable
data class CreateListingRequest(
    val gameAccountId: String,
    val title: String,
    val description: String?,
    val pricePerHour: Long,
    val depositAmount: Long,
    val autoAccept: Boolean,
    val endMode: String,
)
