package com.rentgg.dispute.application;

import com.rentgg.audit.application.AuditService;
import com.rentgg.config.RentGgProperties;
import com.rentgg.deposit.application.DepositService;
import com.rentgg.deposit.application.DepositView;
import com.rentgg.deposit.domain.DepositStatus;
import com.rentgg.catalog.domain.RiskSide;
import com.rentgg.dispute.domain.Dispute;
import com.rentgg.dispute.domain.DisputeAttachment;
import com.rentgg.dispute.domain.DisputeItem;
import com.rentgg.dispute.domain.DisputeStatus;
import com.rentgg.dispute.domain.DisputeType;
import com.rentgg.dispute.domain.EvidenceMedia;
import com.rentgg.dispute.infrastructure.DisputeAttachmentRepository;
import com.rentgg.dispute.infrastructure.DisputeItemRepository;
import com.rentgg.dispute.infrastructure.DisputeRepository;
import com.rentgg.gameaccount.application.GameAccountService;
import com.rentgg.identity.application.AbuseSignalService;
import com.rentgg.identity.application.UserService;
import com.rentgg.identity.domain.UserStatus;
import com.rentgg.ledger.application.LedgerService;
import com.rentgg.ledger.domain.AccountCode;
import com.rentgg.ledger.domain.Posting;
import com.rentgg.ledger.domain.RiskPayoutFee;
import com.rentgg.ledger.domain.TxType;
import com.rentgg.order.application.OrderLifecycleListener;
import com.rentgg.order.application.OrderService;
import com.rentgg.order.application.OrderView;
import com.rentgg.order.domain.Money;
import com.rentgg.order.domain.OrderStatus;
import com.rentgg.payment.application.PaymentService;
import com.rentgg.shared.error.ApiException;
import com.rentgg.shared.outbox.OutboxService;
import com.rentgg.shared.security.CurrentUser;
import com.rentgg.shared.security.Permission;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Disputes (§6.5, §8.5, §8.7): evidence before penalty; deposit deductions limited to that order's deposit. */
@Service
public class DisputeService implements OrderLifecycleListener {

    private static final Set<DisputeType> RENTER_TYPES = Set.of(DisputeType.RENTER_LOGIN_FAILED,
            DisputeType.RENTER_NOT_AS_DESCRIBED, DisputeType.RENTER_RISK);
    private static final Set<DisputeType> DEPOSIT_TYPES = Set.of(DisputeType.OWNER_DAMAGE, DisputeType.ACCOUNT_TAKEOVER,
            DisputeType.OWNER_RISK);
    private static final int MAX_EVIDENCE_URLS = 20;
    static final int MAX_ATTACHMENTS = 10;
    static final int MAX_RISKS_PER_COMPLAINT = 20;

    private final DisputeRepository disputes;
    private final DisputeAttachmentRepository attachments;
    private final DisputeItemRepository items;
    private final OrderService orders;
    private final DepositService deposits;
    private final GameAccountService gameAccounts;
    private final UserService users;
    private final AbuseSignalService signals;
    private final LedgerService ledger;
    private final PaymentService payments;
    private final AuditService audit;
    private final OutboxService outbox;
    private final MeterRegistry meters;
    private final RentGgProperties props;
    private final Clock clock;

    public DisputeService(DisputeRepository disputes, DisputeAttachmentRepository attachments, DisputeItemRepository items,
                          OrderService orders, DepositService deposits,
                          GameAccountService gameAccounts, UserService users, AbuseSignalService signals,
                          LedgerService ledger, PaymentService payments, AuditService audit, OutboxService outbox,
                          MeterRegistry meters, RentGgProperties props, Clock clock) {
        this.disputes = disputes;
        this.attachments = attachments;
        this.items = items;
        this.orders = orders;
        this.deposits = deposits;
        this.gameAccounts = gameAccounts;
        this.users = users;
        this.signals = signals;
        this.ledger = ledger;
        this.payments = payments;
        this.audit = audit;
        this.outbox = outbox;
        this.meters = meters;
        this.props = props;
        this.clock = clock;
    }

    // ================================================================= open

    @Transactional
    public DisputeView open(UUID callerId, UUID orderId, DisputeType type, String description, List<String> evidenceUrls) {
        return open(callerId, orderId, type, (UUID) null, description, evidenceUrls);
    }

    @Transactional
    public DisputeView open(UUID callerId, UUID orderId, DisputeType type, UUID riskId, String description,
                            List<String> evidenceUrls) {
        return open(callerId, orderId, type, riskId == null ? List.of() : List.of(riskId), description, evidenceUrls);
    }

    /**
     * Opens a dispute. OWNER_RISK needs exactly one risk, RENTER_RISK one or more: risks of the order's snapshot on
     * the matching side; each complaint window (if any) runs from the first credential view (RENTER) or the rental
     * end (OWNER). A RENTER_RISK complaint gets one item per risk, decided one by one ({@link #decideItem}).
     */
    @Transactional
    public DisputeView open(UUID callerId, UUID orderId, DisputeType type, List<UUID> riskIds, String description,
                            List<String> evidenceUrls) {
        List<String> urls = validateUrls(evidenceUrls, 0);
        List<UUID> ids = riskIds == null ? List.of() : riskIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        OrderView o = orders.lockView(orderId);
        boolean isRenter = o.renterId().equals(callerId);
        boolean isOwner = o.ownerId().equals(callerId);
        if (!isRenter && !isOwner) {
            throw ApiException.notFound("Đơn thuê");
        }
        List<OrderRisk> risks = new java.util.ArrayList<>();
        if (type.riskBased()) {
            if (ids.isEmpty()) {
                throw ApiException.badRequest("VALIDATION_ERROR", "Hãy chọn ít nhất một rủi ro.");
            }
            if (type == DisputeType.OWNER_RISK && ids.size() > 1) {
                throw ApiException.badRequest("VALIDATION_ERROR", "Khiếu nại của chủ account chỉ gắn với một rủi ro.");
            }
            if (ids.size() > MAX_RISKS_PER_COMPLAINT) {
                throw ApiException.badRequest("VALIDATION_ERROR", "Tối đa " + MAX_RISKS_PER_COMPLAINT + " rủi ro mỗi khiếu nại.");
            }
            for (UUID id : ids) {
                OrderRisk r = OrderRisk.find(o, id);
                if (r == null) {
                    throw ApiException.unprocessable("UNKNOWN_RISK", "Rủi ro không thuộc đơn thuê này.");
                }
                if (r.side() != (type == DisputeType.OWNER_RISK ? RiskSide.OWNER : RiskSide.RENTER)) {
                    throw ApiException.unprocessable("RISK_SIDE_MISMATCH", "Rủi ro này không thuộc loại khiếu nại đã chọn.");
                }
                if (!r.covered()) {
                    throw ApiException.unprocessable("RISK_NOT_COVERED",
                            "Đơn thuê này không có tiền cọc cho rủi ro \"" + r.name() + "\" nên không thể khiếu nại khấu trừ cọc.");
                }
                boolean open = type == DisputeType.RENTER_RISK
                        ? items.existsOpenForRisk(orderId, id) || disputes.existsOpenForRisk(orderId, type, id)
                        : disputes.existsOpenForRisk(orderId, type, id);
                if (open) {
                    throw ApiException.conflict("DISPUTE_ALREADY_OPEN",
                            "Đã có khiếu nại đang mở cho rủi ro \"" + r.name() + "\".");
                }
                risks.add(r);
            }
        } else {
            if (!ids.isEmpty()) {
                throw ApiException.badRequest("VALIDATION_ERROR", "Loại khiếu nại này không gắn với rủi ro.");
            }
            if (disputes.existsByOrderIdAndTypeAndStatus(orderId, type, DisputeStatus.OPEN)) {
                throw ApiException.conflict("DISPUTE_ALREADY_OPEN", "Đã có khiếu nại cùng loại đang mở cho đơn này.");
            }
        }
        Instant now = clock.instant();
        Duration sla = props.dispute().resolutionSla();
        switch (type) {
            case RENTER_RISK -> {
                if (!isRenter) {
                    throw ApiException.forbidden("NOT_RESOURCE_OWNER", "Chỉ người thuê được mở loại khiếu nại này.");
                }
                if (o.status() != OrderStatus.IN_RENTAL) {
                    throw ApiException.unprocessable("COMPLAINT_WINDOW_CLOSED",
                            "Chỉ khiếu nại rủi ro này được trong thời gian thuê.");
                }
                for (OrderRisk risk : risks) {
                    Integer w = risk.complaintWindowMinutes();
                    if (w != null && o.credentialFirstViewedAt() != null
                            && (now.isAfter(o.credentialFirstViewedAt().plus(Duration.ofMinutes(w)))
                                || o.handoverConfirmedAt() != null)) {
                        throw ApiException.unprocessable("COMPLAINT_WINDOW_CLOSED", "Đã quá " + w
                                + " phút kể từ khi bạn xem thông tin tài khoản (hoặc bạn đã xác nhận account dùng được), "
                                + "không thể khiếu nại \"" + risk.name() + "\" nữa.");
                    }
                }
                meters.counter("rentgg_complaint_window_disputes_total", "type", type.name()).increment();
            }
            case OWNER_RISK -> {
                requireOwner(isOwner);
                if (o.status() != OrderStatus.ENDED) {
                    throw ApiException.invalidState("Chỉ khiếu nại thiệt hại sau khi đơn đã kết thúc.");
                }
                OrderRisk risk = risks.get(0);
                Integer w = risk.complaintWindowMinutes();
                if (w != null && o.actualEndAt() != null && now.isAfter(o.actualEndAt().plus(Duration.ofMinutes(w)))) {
                    throw ApiException.unprocessable("COMPLAINT_WINDOW_CLOSED", "Đã quá " + w
                            + " phút kể từ khi đơn kết thúc, không thể khiếu nại \"" + risk.name() + "\" nữa.");
                }
                deposits.markDisputed(orderId, true);
            }
            case RENTER_LOGIN_FAILED, RENTER_NOT_AS_DESCRIBED -> {
                if (!isRenter) {
                    throw ApiException.forbidden("NOT_RESOURCE_OWNER", "Chỉ người thuê được mở loại khiếu nại này.");
                }
                Instant windowEnd = o.complaintWindowEndsAt();
                if (o.status() != OrderStatus.IN_RENTAL || windowEnd == null || now.isAfter(windowEnd)
                        || o.handoverConfirmedAt() != null) {
                    throw ApiException.unprocessable("COMPLAINT_WINDOW_CLOSED",
                            "Đã hết thời gian khiếu nại (tính từ lần xem thông tin đăng nhập đầu tiên).");
                }
                meters.counter("rentgg_complaint_window_disputes_total", "type", type.name()).increment();
            }
            case OWNER_DAMAGE -> {
                requireOwner(isOwner);
                if (o.status() != OrderStatus.ENDED) {
                    throw ApiException.invalidState("Chỉ khiếu nại thiệt hại sau khi đơn đã kết thúc.");
                }
                deposits.markDisputed(orderId, true);
            }
            case ACCOUNT_TAKEOVER -> {
                requireOwner(isOwner);
                if (o.status() == OrderStatus.IN_RENTAL) {
                    orders.endForTakeover(orderId); // credentials stop being viewable immediately
                } else if (o.status() != OrderStatus.ENDED) {
                    throw ApiException.invalidState("Đơn chưa bắt đầu hoặc đã bị hủy.");
                }
                deposits.markDisputed(orderId, true);
                gameAccounts.suspendForTakeover(o.gameAccountId());
                users.changeStatus(callerId, o.renterId(), UserStatus.RESTRICTED, "ACCOUNT_TAKEOVER_REPORTED");
                sla = props.dispute().takeoverResolutionSla();
            }
        }
        // A single-risk complaint keeps the risk on the dispute too (older clients read it from there).
        UUID riskId = risks.size() == 1 ? risks.get(0).riskId() : null;
        Dispute d = new Dispute(orderId, callerId, type, riskId, description, now.plus(sla), now);
        d.addEvidence(urls, null, callerId, now);
        disputes.saveAndFlush(d);
        if (type == DisputeType.RENTER_RISK) {
            for (int i = 0; i < risks.size(); i++) {
                items.save(new DisputeItem(d.getId(), orderId, risks.get(i).riskId(), i, now));
            }
            items.flush();
        }
        if (type == DisputeType.ACCOUNT_TAKEOVER) {
            audit.record(callerId, "ACCOUNT_TAKEOVER_REPORTED", "Order", orderId, null,
                    Map.of("disputeId", d.getId().toString(), "gameAccountId", o.gameAccountId().toString()), null);
        }
        Map<String, Object> opened = new HashMap<>();
        opened.put("type", type.name());
        opened.put("orderId", orderId.toString());
        if (!risks.isEmpty()) {
            if (riskId != null) {
                opened.put("riskId", riskId.toString());
            }
            opened.put("riskIds", risks.stream().map(r -> r.riskId().toString()).toList());
            opened.put("riskName", String.join(", ", risks.stream().map(OrderRisk::name).toList()));
        }
        audit.record(callerId, "DISPUTE_OPENED", "Dispute", d.getId(), null, opened, null);
        outbox.record("Dispute", d.getId(), "DisputeOpened", Map.of("disputeId", d.getId(), "orderId", orderId,
                "type", type, "openedBy", callerId, "deadlineAt", d.getDeadlineAt(),
                "renterId", o.renterId(), "ownerId", o.ownerId(), "urgent", type == DisputeType.ACCOUNT_TAKEOVER));
        return view(d);
    }

    private DisputeView view(Dispute d) {
        return DisputeView.of(d, d.getType() == DisputeType.RENTER_RISK
                ? items.findByDisputeIdOrderByPosition(d.getId()) : List.of());
    }

    /** Views of a page of disputes, loading their items in one query. */
    private List<DisputeView> views(List<Dispute> list) {
        List<UUID> ids = list.stream().filter(d -> d.getType() == DisputeType.RENTER_RISK).map(Dispute::getId).toList();
        Map<UUID, List<DisputeItem>> byDispute = new HashMap<>();
        if (!ids.isEmpty()) {
            for (DisputeItem i : items.findByDisputeIdInOrderByPosition(ids)) {
                byDispute.computeIfAbsent(i.getDisputeId(), k -> new java.util.ArrayList<>()).add(i);
            }
        }
        return list.stream().map(d -> DisputeView.of(d, byDispute.getOrDefault(d.getId(), List.of()))).toList();
    }

    @Transactional
    public DisputeView addEvidence(UUID callerId, UUID disputeId, List<String> evidenceUrls, String note) {
        Dispute d = disputes.findForUpdate(disputeId).orElseThrow(() -> ApiException.notFound("Khiếu nại"));
        OrderView o = orders.getInternal(d.getOrderId());
        if (!o.renterId().equals(callerId) && !o.ownerId().equals(callerId)) {
            throw ApiException.notFound("Khiếu nại");
        }
        if (d.getStatus() != DisputeStatus.OPEN) {
            throw ApiException.invalidState("Khiếu nại đã đóng.");
        }
        List<String> urls = validateUrls(evidenceUrls, d.evidenceUrlCount());
        d.addEvidence(urls, note, callerId, clock.instant());
        outbox.record("Dispute", d.getId(), "DisputeEvidenceAdded", Map.of("disputeId", d.getId(), "orderId", o.id(), "addedBy", callerId,
                "renterId", o.renterId(), "ownerId", o.ownerId()));
        return view(d);
    }

    // ================================================================= attachments (photo / video)

    /**
     * Uploads a photo or video as evidence. Renter and owner of the order only, while the dispute is open.
     * The type comes from the file's magic bytes, never from the client.
     */
    @Transactional
    public DisputeView addAttachment(UUID callerId, UUID disputeId, byte[] data, String note) {
        Dispute d = disputes.findForUpdate(disputeId).orElseThrow(() -> ApiException.notFound("Khiếu nại"));
        OrderView o = orders.getInternal(d.getOrderId());
        if (!o.renterId().equals(callerId) && !o.ownerId().equals(callerId)) {
            throw ApiException.notFound("Khiếu nại");
        }
        if (d.getStatus() != DisputeStatus.OPEN) {
            throw ApiException.invalidState("Khiếu nại đã đóng.");
        }
        if (note != null && note.length() > 4000) {
            throw ApiException.badRequest("VALIDATION_ERROR", "Ghi chú tối đa 4000 ký tự.");
        }
        if (data == null || data.length == 0) {
            throw ApiException.badRequest("VALIDATION_ERROR", "Chưa chọn tệp.");
        }
        String type = EvidenceMedia.detect(data);
        if (type == null) {
            throw ApiException.unprocessable("UNSUPPORTED_MEDIA_TYPE",
                    "Chỉ nhận ảnh JPEG, PNG, WebP hoặc video MP4, MOV, WebM.");
        }
        if (EvidenceMedia.isVideo(type) ? data.length > EvidenceMedia.MAX_VIDEO_BYTES
                : data.length > EvidenceMedia.MAX_IMAGE_BYTES) {
            throw ApiException.unprocessable("FILE_TOO_LARGE",
                    EvidenceMedia.isVideo(type) ? "Video tối đa 20 MB." : "Ảnh tối đa 5 MB.");
        }
        if (attachments.countByDispute(disputeId) >= MAX_ATTACHMENTS) {
            throw ApiException.unprocessable("TOO_MANY_ATTACHMENTS",
                    "Mỗi khiếu nại tối đa " + MAX_ATTACHMENTS + " ảnh/video.");
        }
        DisputeAttachment a = attachments.save(new DisputeAttachment(disputeId, callerId, type, data, clock.instant()));
        d.addAttachmentEvidence(a, note);
        attachments.flush();
        outbox.record("Dispute", d.getId(), "DisputeEvidenceAdded", Map.of("disputeId", d.getId(), "orderId", o.id(), "addedBy", callerId,
                "renterId", o.renterId(), "ownerId", o.ownerId()));
        return view(d);
    }

    /** Attachment bytes; same visibility as the dispute (renter, owner, support). */
    @Transactional(readOnly = true)
    public AttachmentData attachment(UUID callerId, UUID disputeId, UUID attachmentId) {
        getForCaller(callerId, disputeId);
        return attachments.findById(attachmentId)
                .filter(a -> a.getDisputeId().equals(disputeId))
                .map(a -> new AttachmentData(a.getContentType(), a.getData()))
                .orElseThrow(() -> ApiException.notFound("Tệp"));
    }

    public record AttachmentData(String contentType, byte[] data) {}

    // ================================================================= resolve (Support)

    /** {@code compensation}: RENTER_RISK only, owner → renter, at most the owner's compensation for that risk. */
    public record Decision(boolean resolve, long depositDeduction, long rentRefund, long compensation, String reasonCode,
                           String note, UserStatus renterStatus) {

        public Decision(boolean resolve, long depositDeduction, long rentRefund, String reasonCode, String note,
                        UserStatus renterStatus) {
            this(resolve, depositDeduction, rentRefund, 0, reasonCode, note, renterStatus);
        }
    }

    @Transactional
    public DisputeView resolve(UUID supportId, UUID disputeId, Decision dec) {
        if (dec.reasonCode() == null || dec.reasonCode().isBlank()) {
            throw ApiException.badRequest("VALIDATION_ERROR", "reasonCode là bắt buộc.");
        }
        if (dec.depositDeduction() < 0 || dec.rentRefund() < 0 || dec.compensation() < 0) {
            throw ApiException.badRequest("VALIDATION_ERROR", "Số tiền không được âm.");
        }
        Dispute d = disputes.findForUpdate(disputeId).orElseThrow(() -> ApiException.notFound("Khiếu nại"));
        if (d.getStatus() != DisputeStatus.OPEN) {
            return view(d); // natural idempotency
        }
        if (d.getType() == DisputeType.RENTER_RISK && !items.findByDisputeIdOrderByPosition(d.getId()).isEmpty()) {
            throw ApiException.unprocessable("RESOLVE_PER_RISK",
                    "Khiếu nại này gồm nhiều rủi ro: hãy chấp nhận hoặc bác từng rủi ro, tiền sẽ được tính tự động.");
        }
        OrderView o = orders.lockView(d.getOrderId());
        return settle(supportId, d, o, dec);
    }

    /**
     * Support's decision on one risk of a renter complaint. ACCEPTED takes the owner's compensation for that risk
     * from the order snapshot. When the last risk is decided the complaint closes and the money is paid:
     * at least one accepted → the rent still held is refunded once + the sum of the accepted compensations;
     * none accepted → REJECTED, nothing paid.
     */
    @Transactional
    public DisputeView decideItem(UUID supportId, UUID disputeId, UUID itemId, boolean accept, String reasonCode,
                                  String note) {
        if (reasonCode == null || reasonCode.isBlank()) {
            throw ApiException.badRequest("VALIDATION_ERROR", "reasonCode là bắt buộc.");
        }
        if (note != null && note.length() > 4000) {
            throw ApiException.badRequest("VALIDATION_ERROR", "Ghi chú tối đa 4000 ký tự.");
        }
        Dispute d = disputes.findForUpdate(disputeId).orElseThrow(() -> ApiException.notFound("Khiếu nại"));
        List<DisputeItem> list = items.findByDisputeIdOrderByPosition(disputeId);
        DisputeItem item = list.stream().filter(i -> i.getId().equals(itemId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Rủi ro của khiếu nại"));
        if (item.getStatus() != DisputeItem.Status.OPEN || d.getStatus() != DisputeStatus.OPEN) {
            return view(d); // natural idempotency
        }
        OrderView o = orders.lockView(d.getOrderId());
        OrderRisk risk = OrderRisk.find(o, item.getRiskId());
        long compensation = accept && risk != null && risk.amount() != null ? risk.amount() : 0;
        Instant now = clock.instant();
        item.decide(accept, compensation, supportId, reasonCode, note, now);
        items.flush();
        Map<String, Object> after = new HashMap<>();
        after.put("itemId", item.getId().toString());
        after.put("riskId", item.getRiskId().toString());
        after.put("status", item.getStatus().name());
        after.put("compensation", item.getCompensation());
        audit.record(supportId, "DISPUTE_ITEM_DECIDED", "Dispute", d.getId(), Map.of("status", "OPEN"), after, reasonCode);
        if (!accept && "FALSE_CLAIM".equals(reasonCode)) {
            signals.record(o.renterId(), "FALSE_CLAIM", o.id(), Map.of("disputeId", d.getId().toString(),
                    "riskId", item.getRiskId().toString()));
        }
        if (list.stream().anyMatch(i -> i.getStatus() == DisputeItem.Status.OPEN)) {
            return view(d);
        }
        // Last risk decided: compute and pay.
        List<DisputeItem> accepted = list.stream().filter(i -> i.getStatus() == DisputeItem.Status.ACCEPTED).toList();
        boolean anyAccepted = !accepted.isEmpty();
        long totalCompensation = accepted.stream().mapToLong(DisputeItem::getCompensation).sum();
        long refund = anyAccepted ? refundableRent(o, d) : 0;
        String summary = list.size() + " rủi ro: " + accepted.size() + " chấp nhận, " + (list.size() - accepted.size())
                + " bác. Tự tính: hoàn tiền thuê " + refund + " đ, đền bù " + totalCompensation + " đ.";
        return settle(supportId, d, o, new Decision(anyAccepted, 0, refund, totalCompensation,
                anyAccepted ? "RISKS_CONFIRMED" : "RISKS_REJECTED", summary, null));
    }

    /**
     * Rent refunded once when a renter complaint is upheld: what is still refundable of this order, i.e. the rent
     * minus earlier refunds, capped by the rent escrow (rental running) or by the owner's revenue held for the
     * dispute (rental ended; the platform fee part is refunded in proportion).
     */
    private long refundableRent(OrderView o, Dispute current) {
        long earlier = disputes.findByOrderIdOrderByCreatedAt(o.id()).stream()
                .filter(x -> !x.getId().equals(current.getId()) && x.getStatus() == DisputeStatus.RESOLVED)
                .mapToLong(Dispute::getRentRefund).sum();
        long target = Math.max(0, o.rentAmount() - earlier);
        if (target == 0) {
            return 0;
        }
        if (o.status() != OrderStatus.ENDED) {
            return Math.min(target, ledger.liabilityBalanceForOrder(AccountCode.RENT_ESCROW, null, o.id()));
        }
        long held = ledger.liabilityBalanceForOrder(AccountCode.SELLER_DISPUTE_HOLD, o.ownerId(), o.id());
        if (target - Money.proportionalFee(target, o.platformFee(), o.rentAmount()) <= held) {
            return target;
        }
        long sellerShare = o.rentAmount() - o.platformFee();
        long r = sellerShare <= 0 ? target : Math.min(target, held * o.rentAmount() / sellerShare);
        while (r > 0 && r - Money.proportionalFee(r, o.platformFee(), o.rentAmount()) > held) {
            r--;
        }
        return r;
    }

    private DisputeView settle(UUID supportId, Dispute d, OrderView o, Decision dec) {
        long deduction = dec.resolve() ? dec.depositDeduction() : 0;
        long refund = dec.resolve() ? dec.rentRefund() : 0;
        long compensation = dec.resolve() ? dec.compensation() : 0;
        String reference = "dispute:" + d.getId();
        Instant now = clock.instant();
        OrderRisk risk = d.getRiskId() == null ? null : OrderRisk.find(o, d.getRiskId());

        if (d.getType().targetsDeposit()) {
            if (refund != 0 || compensation != 0) {
                throw ApiException.unprocessable("VALIDATION_ERROR", "Khiếu nại của chủ account không hoàn tiền thuê hay đền bù.");
            }
            // A risk-based complaint can take at most the deposit the owner set for that risk.
            if (risk != null && risk.amount() != null && deduction > risk.amount()) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DEDUCTION_EXCEEDS_RISK_DEPOSIT",
                        "Mức khấu trừ vượt quá tiền cọc của rủi ro \"" + risk.name() + "\".",
                        Map.of("riskDeposit", risk.amount()));
            }
            if (deduction > o.depositAmount()) {
                throw ApiException.unprocessable("DEDUCTION_EXCEEDS_DEPOSIT", "Mức khấu trừ vượt quá tiền cọc của đơn.");
            }
            DepositView dep = deposits.getByOrder(o.id());
            if (dep.status() == DepositStatus.DISPUTED) {
                // Several deposit complaints can be open at once; the deposit is settled once, when the last of
                // them closes. Stackable deductions add up; a confirmed exclusive risk (e.g. permanent ban)
                // replaces them: the renter pays only that risk's deposit.
                long total = settlementTotal(o, d, deduction, risk);
                long stackedSum = stackedDeductions(o, d, deduction, risk);
                if (stackedSum > o.depositAmount() || total > o.depositAmount()) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DEDUCTION_EXCEEDS_DEPOSIT",
                            "Tổng khấu trừ của các khiếu nại vượt quá tiền cọc của đơn.",
                            Map.of("remaining", Math.max(0, o.depositAmount() - (stackedSum - deduction))));
                }
                if (!otherOpenDepositDispute(d)) {
                    deposits.settleDispute(o.id(), total, o.ownerId(), supportId, reference);
                }
            } else if (deduction > 0) {
                throw ApiException.invalidState("Tiền cọc của đơn đã được xử lý, không thể khấu trừ thêm.");
            }
            if (d.getType() == DisputeType.ACCOUNT_TAKEOVER && deduction > 0) {
                signals.record(o.renterId(), "RENTER_TAKEOVER_CONFIRMED", o.id(), Map.of("disputeId", d.getId().toString()));
            }
            if (!dec.resolve() && "FALSE_CLAIM".equals(dec.reasonCode())) {
                signals.record(o.ownerId(), "FALSE_CLAIM", o.id(), Map.of("disputeId", d.getId().toString()));
            }
        } else {
            if (deduction != 0) {
                throw ApiException.unprocessable("VALIDATION_ERROR", "Khiếu nại của người thuê không khấu trừ cọc.");
            }
            List<DisputeItem> decided = d.getType() == DisputeType.RENTER_RISK
                    ? items.findByDisputeIdOrderByPosition(d.getId()) : List.of();
            long maxCompensation = !decided.isEmpty()
                    ? decided.stream().filter(i -> i.getStatus() == DisputeItem.Status.ACCEPTED)
                            .mapToLong(DisputeItem::getCompensation).sum()
                    : risk == null || risk.amount() == null ? 0 : risk.amount();
            if (compensation > maxCompensation) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "COMPENSATION_EXCEEDS_TERMS",
                        maxCompensation == 0 ? "Chủ account không đặt đền bù cho rủi ro này, chỉ hoàn tiền thuê."
                                : "Mức đền bù vượt quá mức chủ account đã cam kết.",
                        Map.of("maxCompensation", maxCompensation));
            }
            if (refund > 0) {
                refundRent(d, o, refund, reference, supportId);
                if (d.getType() == DisputeType.RENTER_LOGIN_FAILED) {
                    signals.record(o.ownerId(), "OWNER_LOGIN_FAILED_CONFIRMED", o.id(), Map.of("disputeId", d.getId().toString()));
                }
            }
            if (compensation > 0) {
                payCompensation(d, o, compensation, reference, supportId);
            }
            if (!dec.resolve() && "FALSE_CLAIM".equals(dec.reasonCode())) {
                signals.record(o.renterId(), "FALSE_CLAIM", o.id(), Map.of("disputeId", d.getId().toString()));
            }
        }

        d.decide(dec.resolve() ? DisputeStatus.RESOLVED : DisputeStatus.REJECTED, deduction, refund, compensation,
                supportId, dec.reasonCode(), dec.note(), now);
        disputes.flush();

        // Renter dispute closed after the order ended: give the remaining revenue back to the review hold.
        if (d.getType().byRenter() && o.status() == OrderStatus.ENDED && !disputes.existsOpenOfTypes(o.id(), RENTER_TYPES)) {
            long held = ledger.liabilityBalanceForOrder(AccountCode.SELLER_DISPUTE_HOLD, o.ownerId(), o.id());
            if (held > 0) {
                ledger.post(TxType.SELLER_DISPUTE_HOLD_RELEASED, o.id(), reference, "SELLER_DISPUTE_HOLD_RELEASED:" + d.getId(),
                        supportId, List.of(Posting.debit(AccountCode.SELLER_DISPUTE_HOLD, o.ownerId(), held),
                                Posting.credit(AccountCode.SELLER_REVIEW_HOLD, o.ownerId(), held)));
            }
        }
        if (dec.renterStatus() != null) {
            users.changeStatus(supportId, o.renterId(), dec.renterStatus(), dec.reasonCode());
        }
        Map<String, Object> after = new HashMap<>();
        after.put("status", d.getStatus().name());
        after.put("depositDeduction", deduction);
        after.put("rentRefund", refund);
        after.put("compensation", compensation);
        audit.record(supportId, "DISPUTE_RESOLVED", "Dispute", d.getId(), Map.of("status", "OPEN"), after, dec.reasonCode());
        outbox.record("Dispute", d.getId(), "DisputeResolved", Map.of("disputeId", d.getId(), "orderId", o.id(),
                "status", d.getStatus(), "depositDeduction", deduction, "rentRefund", refund, "compensation", compensation,
                "reasonCode", dec.reasonCode(), "renterId", o.renterId(), "ownerId", o.ownerId()));
        meters.timer("rentgg_dispute_resolution_seconds", "type", d.getType().name())
                .record(Duration.between(d.getCreatedAt(), now));
        return view(d);
    }

    private void refundRent(Dispute d, OrderView o, long refund, String reference, UUID supportId) {
        if (refund > o.rentAmount()) {
            throw ApiException.unprocessable("REFUND_EXCEEDS_RENT", "Số tiền hoàn vượt quá tiền thuê.");
        }
        String key = "RENT_REFUND:" + d.getId();
        if (o.status() != OrderStatus.ENDED) {
            long escrow = ledger.liabilityBalanceForOrder(AccountCode.RENT_ESCROW, null, o.id());
            if (refund > escrow) {
                throw ApiException.unprocessable("REFUND_EXCEEDS_RENT", "Số tiền hoàn vượt quá tiền thuê còn giữ.");
            }
            ledger.post(TxType.RENT_REFUND, o.id(), reference, key, supportId,
                    List.of(Posting.debit(AccountCode.RENT_ESCROW, refund), Posting.credit(AccountCode.REFUND_PAYABLE, refund)));
        } else {
            long feePart = Money.proportionalFee(refund, o.platformFee(), o.rentAmount());
            long sellerPart = refund - feePart;
            long held = ledger.liabilityBalanceForOrder(AccountCode.SELLER_DISPUTE_HOLD, o.ownerId(), o.id());
            if (sellerPart > held) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "REFUND_EXCEEDS_RENT",
                        "Số tiền hoàn vượt quá doanh thu đang bị giữ do khiếu nại.", Map.of("disputeHold", held));
            }
            ledger.post(TxType.RENT_REFUND, o.id(), reference, key, supportId,
                    List.of(Posting.debit(AccountCode.SELLER_DISPUTE_HOLD, o.ownerId(), sellerPart),
                            Posting.debit(AccountCode.PLATFORM_REVENUE, feePart),
                            Posting.credit(AccountCode.REFUND_PAYABLE, refund)));
        }
        payments.requestRefund(o.id(), null, "RENT", refund, reference);
    }

    /**
     * Owner's compensation for a confirmed RENTER risk: taken first from this order's revenue still held for the
     * dispute, then from the owner's wallet; credited to the renter's wallet.
     */
    private void payCompensation(Dispute d, OrderView o, long amount, String reference, UUID supportId) {
        long fromHold = 0;
        if (o.status() == OrderStatus.ENDED) {
            fromHold = Math.min(amount, ledger.liabilityBalanceForOrder(AccountCode.SELLER_DISPUTE_HOLD, o.ownerId(), o.id()));
        }
        long fromWallet = amount - fromHold;
        if (fromWallet > 0) {
            ledger.lockSellerAvailable(o.ownerId());
            long available = ledger.liabilityBalance(AccountCode.SELLER_AVAILABLE, o.ownerId());
            if (available < fromWallet) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "OWNER_BALANCE_INSUFFICIENT",
                        "Ví của chủ account không đủ tiền để đền bù.", Map.of("available", available + fromHold));
            }
        }
        // The owner pays the full amount; the platform keeps its risk payout fee, the renter gets the rest.
        long fee = RiskPayoutFee.of(amount);
        ledger.post(TxType.RISK_COMPENSATION, o.id(), reference, "RISK_COMPENSATION:" + d.getId(), supportId,
                List.of(Posting.debit(AccountCode.SELLER_DISPUTE_HOLD, o.ownerId(), fromHold),
                        Posting.debit(AccountCode.SELLER_AVAILABLE, o.ownerId(), fromWallet),
                        Posting.credit(AccountCode.SELLER_AVAILABLE, o.renterId(), amount - fee),
                        Posting.credit(AccountCode.PLATFORM_REVENUE, fee)));
    }

    /** Earlier resolved deposit complaints of the order (the deposit is still DISPUTED, so none is settled yet). */
    private List<Dispute> otherResolvedDepositDisputes(OrderView o, Dispute current) {
        return disputes.findByOrderIdOrderByCreatedAt(o.id()).stream()
                .filter(x -> !x.getId().equals(current.getId()) && x.getStatus() == DisputeStatus.RESOLVED
                        && DEPOSIT_TYPES.contains(x.getType()))
                .toList();
    }

    private static boolean exclusive(OrderRisk r) {
        return r != null && r.side() == RiskSide.OWNER && !r.stackable();
    }

    /** Sum of the stackable deductions, this decision included. */
    private long stackedDeductions(OrderView o, Dispute current, long deduction, OrderRisk currentRisk) {
        long sum = exclusive(currentRisk) ? 0 : deduction;
        for (Dispute x : otherResolvedDepositDisputes(o, current)) {
            if (!exclusive(OrderRisk.find(o, x.getRiskId()))) {
                sum += x.getDepositDeduction();
            }
        }
        return sum;
    }

    /** What the deposit settles for: the largest confirmed exclusive deduction if any, else the stackable sum. */
    private long settlementTotal(OrderView o, Dispute current, long deduction, OrderRisk currentRisk) {
        long exclusiveMax = exclusive(currentRisk) ? deduction : 0;
        for (Dispute x : otherResolvedDepositDisputes(o, current)) {
            if (exclusive(OrderRisk.find(o, x.getRiskId()))) {
                exclusiveMax = Math.max(exclusiveMax, x.getDepositDeduction());
            }
        }
        return exclusiveMax > 0 ? exclusiveMax : stackedDeductions(o, current, deduction, currentRisk);
    }

    private boolean otherOpenDepositDispute(Dispute d) {
        return disputes.existsOpenOfTypesExcept(d.getOrderId(), DEPOSIT_TYPES, d.getId());
    }

    // ================================================================= hooks & queries

    /** Order ended while a renter dispute is open → revenue goes from review hold to dispute hold (§8.9). */
    @Override
    public void afterOrderEnded(OrderView o) {
        if (!disputes.existsOpenOfTypes(o.id(), RENTER_TYPES)) {
            return;
        }
        long review = ledger.liabilityBalanceForOrder(AccountCode.SELLER_REVIEW_HOLD, o.ownerId(), o.id());
        if (review > 0) {
            ledger.post(TxType.SELLER_DISPUTE_HOLD, o.id(), "order-ended", "SELLER_DISPUTE_HOLD:" + o.id(), null,
                    List.of(Posting.debit(AccountCode.SELLER_REVIEW_HOLD, o.ownerId(), review),
                            Posting.credit(AccountCode.SELLER_DISPUTE_HOLD, o.ownerId(), review)));
        }
    }

    @Transactional(readOnly = true)
    public boolean hasOpenRenterDispute(UUID orderId) {
        return disputes.existsOpenOfTypes(orderId, RENTER_TYPES);
    }

    @Transactional(readOnly = true)
    public DisputeView getForCaller(UUID callerId, UUID disputeId) {
        Dispute d = disputes.findById(disputeId).orElseThrow(() -> ApiException.notFound("Khiếu nại"));
        OrderView o = orders.getInternal(d.getOrderId());
        if (!o.renterId().equals(callerId) && !o.ownerId().equals(callerId)
                && !CurrentUser.has(Permission.DISPUTE_READ_ANY)) {
            throw ApiException.notFound("Khiếu nại");
        }
        return view(d);
    }

    @Transactional(readOnly = true)
    public List<DisputeView> forOrder(UUID orderId) {
        return views(disputes.findByOrderIdOrderByCreatedAt(orderId));
    }

    @Transactional(readOnly = true)
    public Page<DisputeView> search(DisputeStatus status, DisputeType type, Pageable pageable) {
        Page<Dispute> page;
        if (status != null && type != null) {
            page = disputes.findByStatusAndType(status, type, pageable);
        } else if (status != null) {
            page = disputes.findByStatus(status, pageable);
        } else if (type != null) {
            page = disputes.findByType(type, pageable);
        } else {
            page = disputes.findAll(pageable);
        }
        return new org.springframework.data.domain.PageImpl<>(views(page.getContent()), pageable, page.getTotalElements());
    }

    /** Same as {@link #search} plus a free-text query (see {@link DisputeRepository#searchIds}). */
    @Transactional(readOnly = true)
    public Page<DisputeView> search(String query, DisputeStatus status, DisputeType type, Pageable pageable) {
        String q = query == null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT);
        if (q.isEmpty()) return search(status, type, pageable);
        q = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        String s = status == null ? "" : status.name();
        String t = type == null ? "" : type.name();
        List<UUID> ids = disputes.searchIds(q, s, t, pageable.getPageSize(), pageable.getOffset());
        java.util.Map<UUID, Dispute> byId = new java.util.HashMap<>();
        disputes.findAllById(ids).forEach(d -> byId.put(d.getId(), d));
        List<DisputeView> content = views(ids.stream().map(byId::get).filter(java.util.Objects::nonNull).toList());
        return new org.springframework.data.domain.PageImpl<>(content, pageable, disputes.countSearch(q, s, t));
    }

    private static void requireOwner(boolean isOwner) {
        if (!isOwner) {
            throw ApiException.forbidden("NOT_RESOURCE_OWNER", "Chỉ chủ account được mở loại khiếu nại này.");
        }
    }

    private static List<String> validateUrls(List<String> urls, int existing) {
        List<String> list = urls == null ? List.of() : urls;
        if (existing + list.size() > MAX_EVIDENCE_URLS) {
            throw ApiException.badRequest("VALIDATION_ERROR", "Tối đa " + MAX_EVIDENCE_URLS + " URL bằng chứng.");
        }
        for (String u : list) {
            if (u == null || !u.startsWith("https://") || u.length() > 2000) {
                throw ApiException.badRequest("VALIDATION_ERROR", "Bằng chứng phải là URL https://");
            }
        }
        return list;
    }
}
