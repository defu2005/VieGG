package com.rentgg.listing.application;

import com.rentgg.audit.application.AuditService;
import com.rentgg.catalog.application.CatalogService;
import com.rentgg.catalog.application.GameView;
import com.rentgg.catalog.domain.HandoverMode;
import com.rentgg.gameaccount.application.GameAccountService;
import com.rentgg.gameaccount.application.GameAccountView;
import com.rentgg.gameaccount.domain.SecurityState;
import com.rentgg.identity.application.UserService;
import com.rentgg.listing.domain.EndMode;
import com.rentgg.listing.domain.Listing;
import com.rentgg.listing.domain.ListingImage;
import com.rentgg.listing.domain.ListingStatus;
import com.rentgg.listing.domain.PricingTier;
import com.rentgg.listing.domain.RiskTerm;
import com.rentgg.catalog.application.GameRiskView;
import com.rentgg.catalog.domain.RiskSide;
import com.rentgg.listing.infrastructure.ListingImageRepository;
import com.rentgg.listing.infrastructure.ListingRepository;
import com.rentgg.shared.error.ApiException;
import com.rentgg.shared.error.DbErrors;
import com.rentgg.shared.outbox.OutboxService;
import com.rentgg.shared.web.PageResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.rentgg.listing.domain.ListingStatus.*;

@Service
public class ListingService {

    static final int MAX_IMAGES = 8;
    static final int MAX_IMAGE_BYTES = 1024 * 1024;

    private final ListingRepository listings;
    private final ListingImageRepository images;
    private final GameAccountService gameAccounts;
    private final CatalogService catalog;
    private final UserService users;
    private final AuditService audit;
    private final OutboxService outbox;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public ListingService(ListingRepository listings, ListingImageRepository images, GameAccountService gameAccounts,
                          CatalogService catalog, UserService users, AuditService audit, OutboxService outbox,
                          NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.listings = listings;
        this.images = images;
        this.gameAccounts = gameAccounts;
        this.catalog = catalog;
        this.users = users;
        this.audit = audit;
        this.outbox = outbox;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Editable listing content. With {@code pricingTiers} the hourly price is derived from the shortest package
     * (used for search and sorting); without them the listing is priced per hour as before.
     * {@code ownerRisks}: the risks the owner adds to this listing on top of the game's; null = keep the current ones
     * (clients that do not know about them), empty = none.
     */
    public record Content(String title, String description, Map<String, Object> attributes, long pricePerHour,
                          List<PricingTier> pricingTiers, long depositAmount, List<RiskTerm> riskTerms,
                          boolean autoAccept, EndMode endMode, List<OwnerRisk> ownerRisks,
                          Integer renterFreeCancelMinutes) {

        /** Max free-cancel window an owner can set: 7 days. */
        public static final int MAX_FREE_CANCEL_MINUTES = 7 * 24 * 60;

        /** Keeps the listing's current free-cancel window. */
        public Content(String title, String description, Map<String, Object> attributes, long pricePerHour,
                       List<PricingTier> pricingTiers, long depositAmount, List<RiskTerm> riskTerms,
                       boolean autoAccept, EndMode endMode, List<OwnerRisk> ownerRisks) {
            this(title, description, attributes, pricePerHour, pricingTiers, depositAmount, riskTerms, autoAccept,
                    endMode, ownerRisks, null);
        }

        public Content {
            pricingTiers = PricingTier.sorted(pricingTiers);
            if (!pricingTiers.isEmpty() && pricingTiers.stream().allMatch(t -> t.durationMinutes() > 0 && t.price() > 0)) {
                pricePerHour = PricingTier.hourlyPriceOf(pricingTiers);
            }
            riskTerms = riskTerms == null ? List.of() : List.copyOf(riskTerms);
            ownerRisks = ownerRisks == null ? null : ownerRisks.stream().filter(Objects::nonNull).toList();
        }

        /** Keeps the listing's own risks as they are. */
        public Content(String title, String description, Map<String, Object> attributes, long pricePerHour,
                       List<PricingTier> pricingTiers, long depositAmount, List<RiskTerm> riskTerms,
                       boolean autoAccept, EndMode endMode) {
            this(title, description, attributes, pricePerHour, pricingTiers, depositAmount, riskTerms, autoAccept,
                    endMode, null);
        }

        /** No per-risk terms: for games without risks. */
        public Content(String title, String description, Map<String, Object> attributes, long pricePerHour,
                       List<PricingTier> pricingTiers, long depositAmount, boolean autoAccept, EndMode endMode) {
            this(title, description, attributes, pricePerHour, pricingTiers, depositAmount, List.of(), autoAccept, endMode);
        }

        /** Hourly pricing only (no packages). */
        public Content(String title, String description, Map<String, Object> attributes, long pricePerHour,
                       long depositAmount, boolean autoAccept, EndMode endMode) {
            this(title, description, attributes, pricePerHour, List.of(), depositAmount, autoAccept, endMode);
        }

        Content withRisks(long deposit, List<RiskTerm> terms) {
            return new Content(title, description, attributes, pricePerHour, pricingTiers, deposit, terms, autoAccept,
                    endMode, ownerRisks, renterFreeCancelMinutes);
        }
    }

    /**
     * A risk the owner adds to their listing. {@code id} null = new. {@code amount}: the deposit (OWNER) or the
     * compensation (RENTER, optional), same meaning as a {@link RiskTerm}. {@code stackable} only matters for OWNER.
     */
    public record OwnerRisk(UUID id, RiskSide side, String name, String description, Integer complaintWindowMinutes,
                            boolean stackable, Long amount) {}

    /** The content checked against the game plus the listing's own risks, and those risks to save (null = keep). */
    private record Prepared(Content content, List<CatalogService.RiskInput> ownerRisks, boolean ownerRisksTextChanged) {}

    /**
     * Resolves the listing's own risks (new ones get their id here, so the terms can point at them), then checks
     * the content against the game's risks plus those. {@code listingId} null = the listing does not exist yet.
     */
    private Prepared prepare(GameView game, UUID listingId, List<RiskTerm> storedTerms, Content c) {
        List<GameRiskView> current = listingId == null ? List.of() : catalog.listingRisks(listingId);
        if (c.ownerRisks() == null) {
            // Client unaware of owner risks: keep them, and their amounts unless the client sent new ones.
            Map<UUID, RiskTerm> terms = new HashMap<>();
            for (RiskTerm t : storedTerms) {
                if (current.stream().anyMatch(r -> r.id().equals(t.riskId()))) {
                    terms.put(t.riskId(), t);
                }
            }
            for (RiskTerm t : c.riskTerms()) {
                if (t != null && t.riskId() != null) {
                    terms.put(t.riskId(), t);
                }
            }
            Content checked = validateContent(game.withListingRisks(current),
                    c.withRisks(c.depositAmount(), List.copyOf(terms.values())));
            return new Prepared(checked, null, false);
        }
        Set<UUID> known = listingId == null ? Set.of() : catalog.listingRiskIds(listingId);
        List<CatalogService.RiskInput> inputs = new ArrayList<>();
        List<GameRiskView> views = new ArrayList<>();
        Map<UUID, RiskTerm> terms = new HashMap<>();
        for (RiskTerm t : c.riskTerms()) {
            // An owner risk's amount comes only from its OwnerRisk.amount; terms for those ids are ignored.
            if (t != null && t.riskId() != null && !known.contains(t.riskId())) {
                terms.put(t.riskId(), t);
            }
        }
        for (OwnerRisk o : c.ownerRisks()) {
            UUID id = o.id();
            if (id == null) {
                id = UUID.randomUUID();
            } else if (!known.contains(id) || inputs.stream().anyMatch(i -> i.id().equals(o.id()))) {
                throw ApiException.notFound("Rủi ro");
            }
            CatalogService.RiskInput in = new CatalogService.RiskInput(id, o.side(), o.name(), o.description(), null,
                    o.complaintWindowMinutes(), o.stackable());
            inputs.add(in);
            if (o.amount() != null) {
                terms.put(id, new RiskTerm(id, o.amount()));
            }
        }
        CatalogService.validateListingRisks(game, inputs);
        for (CatalogService.RiskInput in : inputs) {
            String description = in.description() == null || in.description().isBlank() ? null : in.description().trim();
            views.add(new GameRiskView(in.id(), in.side(), in.name().trim(), description, null,
                    in.complaintWindowMinutes(), in.side() != RiskSide.OWNER || in.stackable(), true));
        }
        Content checked = validateContent(game.withListingRisks(views),
                c.withRisks(c.depositAmount(), List.copyOf(terms.values())));
        // What renters read about each risk (side, name, description) is reviewed like the listing text.
        boolean textChanged = current.size() != views.size();
        for (int i = 0; !textChanged && i < views.size(); i++) {
            GameRiskView a = current.get(i);
            GameRiskView b = views.get(i);
            textChanged = !a.id().equals(b.id()) || a.side() != b.side() || !a.name().equals(b.name())
                    || !Objects.equals(a.description(), b.description());
        }
        return new Prepared(checked, inputs, textChanged);
    }

    @Transactional
    public ListingView create(UUID ownerId, UUID gameAccountId, Content c) {
        users.requireEligible(ownerId, 18, false);
        // Lock the account row so two concurrent creates for the same account cannot both pass the check below.
        GameAccountView account = gameAccounts.getOwnedForUpdate(ownerId, gameAccountId);
        // One listing per account, whatever its status: an existing one is edited and resubmitted, not duplicated.
        if (listings.existsByGameAccountId(gameAccountId)) {
            throw ApiException.conflict("LISTING_ALREADY_EXISTS",
                    "Account này đã có listing. Hãy sửa hoặc gửi duyệt lại listing đó thay vì tạo mới.");
        }
        GameView game = catalog.get(account.gameId());
        Prepared p = prepare(game, null, List.of(), c);
        c = p.content();
        Listing l = new Listing(gameAccountId, ownerId, account.gameId(), clock.instant());
        l.applyContent(c.title(), c.description(), c.attributes(), c.pricePerHour(), c.pricingTiers(), c.depositAmount(),
                c.riskTerms(), c.autoAccept(), c.endMode(), clock.instant());
        l.applyRenterFreeCancelMinutes(c.renterFreeCancelMinutes(), clock.instant());
        listings.save(l);
        if (p.ownerRisks() != null && !p.ownerRisks().isEmpty()) {
            listings.flush(); // the risks point at the listing row
            catalog.saveListingRisks(l.getGameId(), l.getId(), p.ownerRisks());
        }
        return view(l);
    }

    @Transactional
    public ListingView update(UUID ownerId, UUID listingId, Long expectedVersion, Content c) {
        Listing l = lockOwned(ownerId, listingId);
        if (expectedVersion != null && !Objects.equals(expectedVersion, l.getVersion())) {
            throw ApiException.conflict("CONCURRENT_MODIFICATION", "Listing đã bị thay đổi, vui lòng tải lại.");
        }
        if (l.getStatus() == SUSPENDED) {
            throw ApiException.invalidState("Listing đã bị đình chỉ.");
        }
        Prepared p = prepare(catalog.get(l.getGameId()), listingId, l.getRiskTerms(), c);
        c = p.content();
        Listing.ContentChange change = l.applyContent(c.title(), c.description(), c.attributes(), c.pricePerHour(),
                c.pricingTiers(), c.depositAmount(), c.riskTerms(), c.autoAccept(), c.endMode(), clock.instant());
        // Null from a client that does not know the field keeps the current window.
        Integer oldFreeCancel = l.getRenterFreeCancelMinutes();
        boolean freeCancelChanged = c.renterFreeCancelMinutes() != null
                && l.applyRenterFreeCancelMinutes(c.renterFreeCancelMinutes(), clock.instant());
        if (freeCancelChanged) {
            audit.record(ownerId, "LISTING_TERMS_CHANGED", "Listing", listingId,
                    Map.of("renterFreeCancelMinutes", String.valueOf(oldFreeCancel)),
                    Map.of("renterFreeCancelMinutes", c.renterFreeCancelMinutes()), null);
        }
        if (p.ownerRisks() != null) {
            catalog.saveListingRisks(l.getGameId(), listingId, p.ownerRisks());
            if (p.ownerRisksTextChanged()) {
                // Version bump even when no listing column changed, so editors holding an older copy reload.
                l.touch(clock.instant());
                audit.record(ownerId, "LISTING_OWNER_RISKS_CHANGED", "Listing", listingId, null,
                        Map.of("risks", p.ownerRisks().stream().map(r -> Map.of("id", r.id().toString(),
                                "side", r.side().name(), "name", r.name().trim())).toList()), null);
            }
        }
        if (change.termsChanged()) {
            // No review needed for price/terms, but keep a record admins can look up.
            audit.record(ownerId, "LISTING_TERMS_CHANGED", "Listing", listingId,
                    Map.of("pricePerHour", change.oldPricePerHour(), "depositAmount", change.oldDepositAmount(),
                            "autoAccept", change.oldAutoAccept(), "endMode", String.valueOf(change.oldEndMode())),
                    Map.of("pricePerHour", c.pricePerHour(), "depositAmount", c.depositAmount(),
                            "autoAccept", c.autoAccept(), "endMode", String.valueOf(c.endMode())),
                    null);
        }
        if (l.getStatus() == REJECTED) {
            move(l, DRAFT, null);
        } else if (l.getStatus() == ACTIVE && (change.needsReview() || p.ownerRisksTextChanged())) {
            // Existing orders keep their policy_snapshot; new renter-facing content needs review again.
            move(l, PENDING_REVIEW, null);
        }
        return view(l);
    }

    @Transactional
    public ListingView submit(UUID ownerId, UUID listingId) {
        Listing l = lockOwned(ownerId, listingId);
        if (l.getStatus() == PENDING_REVIEW) {
            return view(l);
        }
        if (l.getStatus() != DRAFT && l.getStatus() != PAUSED) {
            throw ApiException.invalidState("Chỉ gửi duyệt được listing ở trạng thái DRAFT hoặc PAUSED.");
        }
        catalog.requireAllowed(l.getGameId());
        requireListable(gameAccounts.get(l.getGameAccountId()), catalog.get(l.getGameId()), "gửi duyệt");
        GameView withOwn = catalog.get(l.getGameId()).withListingRisks(catalog.listingRisks(listingId));
        validateContent(withOwn, new Content(l.getTitle(), l.getDescription(), l.getAttributes(),
                l.getPricePerHour(), l.getPricingTiers(), l.getDepositAmount(), l.getRiskTerms(), l.isAutoAccept(),
                l.getEndMode()));
        move(l, PENDING_REVIEW, null);
        return view(l);
    }

    /** Same account checks for submit and approve, so a listing can only become ACTIVE on a verified, unsuspended account. */
    private static void requireListable(GameAccountView account, GameView game, String action) {
        if (!account.recoveryVerified() || account.securityState() == SecurityState.PENDING_VERIFICATION) {
            throw ApiException.unprocessable("RECOVERY_NOT_VERIFIED",
                    "Account chưa được xác minh thông tin khôi phục, chưa thể " + action + ".");
        }
        // Accounts added before the game got an identity rule must fill in their identifier first.
        if (!game.accountKey().usesLogin() && account.accountKey() == null) {
            throw ApiException.unprocessable("ACCOUNT_KEY_REQUIRED",
                    "Account chưa có " + game.accountKeyLabel() + ", cần bổ sung trước khi " + action + ".");
        }
        if (account.securityState() == SecurityState.SUSPENDED_SECURITY_REVIEW) {
            throw ApiException.conflict("ACCOUNT_NOT_AVAILABLE",
                    "Account đang bị tạm khóa để rà soát bảo mật, chưa thể " + action + ".");
        }
    }

    @Transactional
    public ListingView pause(UUID ownerId, UUID listingId) {
        Listing l = lockOwned(ownerId, listingId);
        if (l.getStatus() == PAUSED) {
            return view(l);
        }
        if (l.getStatus() != ACTIVE) {
            throw ApiException.invalidState("Chỉ tạm dừng được listing đang ACTIVE.");
        }
        move(l, PAUSED, null);
        return view(l);
    }

    @Transactional
    public ListingView approve(UUID adminId, UUID listingId) {
        Listing l = listings.findForUpdate(listingId).orElseThrow(() -> ApiException.notFound("Listing"));
        if (l.getStatus() == ACTIVE) {
            return view(l);
        }
        if (l.getStatus() != PENDING_REVIEW) {
            throw ApiException.invalidState("Listing không ở trạng thái chờ duyệt.");
        }
        catalog.requireAllowed(l.getGameId());
        // The account may have been suspended or sent back to verification while the listing waited for review.
        requireListable(gameAccounts.get(l.getGameAccountId()), catalog.get(l.getGameId()), "duyệt");
        move(l, ACTIVE, null);
        try {
            listings.flush();
        } catch (DataIntegrityViolationException e) {
            ApiException mapped = DbErrors.map(e);
            throw mapped != null ? mapped : e;
        }
        audit.record(adminId, "LISTING_APPROVED", "Listing", listingId, Map.of("status", "PENDING_REVIEW"),
                Map.of("status", "ACTIVE"), null);
        return view(l);
    }

    @Transactional
    public ListingView reject(UUID adminId, UUID listingId, String note) {
        if (note == null || note.isBlank()) {
            throw ApiException.badRequest("VALIDATION_ERROR", "Cần ghi chú lý do từ chối.");
        }
        Listing l = listings.findForUpdate(listingId).orElseThrow(() -> ApiException.notFound("Listing"));
        if (l.getStatus() == REJECTED) {
            return view(l);
        }
        if (l.getStatus() != PENDING_REVIEW) {
            throw ApiException.invalidState("Listing không ở trạng thái chờ duyệt.");
        }
        move(l, REJECTED, note);
        audit.record(adminId, "LISTING_REJECTED", "Listing", listingId, Map.of("status", "PENDING_REVIEW"),
                Map.of("status", "REJECTED", "note", note), null);
        return view(l);
    }

    @Transactional
    public ListingView suspend(UUID adminId, UUID listingId, String reasonCode) {
        if (reasonCode == null || reasonCode.isBlank()) {
            throw ApiException.badRequest("VALIDATION_ERROR", "reasonCode là bắt buộc.");
        }
        Listing l = listings.findForUpdate(listingId).orElseThrow(() -> ApiException.notFound("Listing"));
        if (l.getStatus() == SUSPENDED) {
            return view(l);
        }
        if (l.getStatus() != ACTIVE && l.getStatus() != PAUSED && l.getStatus() != PENDING_REVIEW) {
            throw ApiException.invalidState("Không thể đình chỉ listing ở trạng thái " + l.getStatus());
        }
        ListingStatus from = l.getStatus();
        move(l, SUSPENDED, reasonCode);
        audit.record(adminId, "LISTING_SUSPENDED", "Listing", listingId, Map.of("status", from.name()),
                Map.of("status", "SUSPENDED"), reasonCode);
        return view(l);
    }

    @Transactional(readOnly = true)
    public ListingView get(UUID listingId) {
        return view(listings.findById(listingId).orElseThrow(() -> ApiException.notFound("Listing")));
    }

    @Transactional(readOnly = true)
    public List<ListingView> listByOwner(UUID ownerId) {
        return views(listings.findByOwnerIdOrderByCreatedAtDesc(ownerId));
    }

    @Transactional(readOnly = true)
    public List<ListingView> listByStatus(ListingStatus status) {
        return views(listings.findByStatusOrderByUpdatedAt(status));
    }

    /** Paged review queue, oldest update first. */
    @Transactional(readOnly = true)
    public PageResponse<ListingView> listByStatus(ListingStatus status, int page, int size) {
        return pageOf(listings.findByStatus(status, org.springframework.data.domain.PageRequest.of(page, size,
                org.springframework.data.domain.Sort.by("updatedAt", "id"))));
    }

    /** Paged owner listings, newest first; optionally only those of the given game accounts. */
    @Transactional(readOnly = true)
    public PageResponse<ListingView> listByOwner(UUID ownerId, java.util.Collection<UUID> gameAccountIds, int page, int size) {
        var pageable = org.springframework.data.domain.PageRequest.of(page, size, org.springframework.data.domain.Sort.by(
                org.springframework.data.domain.Sort.Direction.DESC, "createdAt", "id"));
        return pageOf(gameAccountIds == null || gameAccountIds.isEmpty()
                ? listings.findByOwnerId(ownerId, pageable)
                : listings.findByOwnerIdAndGameAccountIdIn(ownerId, gameAccountIds, pageable));
    }

    private PageResponse<ListingView> pageOf(org.springframework.data.domain.Page<Listing> result) {
        return PageResponse.of(views(result.getContent()), result.getNumber(), result.getSize(), result.getTotalElements());
    }

    // ---------------------------------------------------------------- images

    /**
     * Adds an account screenshot. The type is taken from the file's magic bytes, never from the client.
     * New images change what renters see, so an ACTIVE listing goes back to review (same rule as a text edit).
     */
    @Transactional
    public ListingView addImage(UUID ownerId, UUID listingId, byte[] data) {
        Listing l = lockOwned(ownerId, listingId);
        if (l.getStatus() == SUSPENDED) {
            throw ApiException.invalidState("Listing đã bị đình chỉ.");
        }
        if (data == null || data.length == 0) {
            throw ApiException.badRequest("VALIDATION_ERROR", "Chưa chọn ảnh.");
        }
        if (data.length > MAX_IMAGE_BYTES) {
            throw ApiException.unprocessable("IMAGE_TOO_LARGE", "Ảnh tối đa 1 MB.");
        }
        String type = detectImageType(data);
        if (type == null) {
            throw ApiException.unprocessable("UNSUPPORTED_IMAGE_TYPE", "Chỉ nhận ảnh JPEG, PNG hoặc WebP.");
        }
        if (images.countByListing(listingId) >= MAX_IMAGES) {
            throw ApiException.unprocessable("TOO_MANY_IMAGES", "Mỗi listing tối đa " + MAX_IMAGES + " ảnh.");
        }
        Instant now = clock.instant();
        images.save(new ListingImage(listingId, images.maxPosition(listingId) + 1, type, data, now));
        if (l.getStatus() == REJECTED) {
            move(l, DRAFT, null);
        } else if (l.getStatus() == ACTIVE) {
            move(l, PENDING_REVIEW, null);
        }
        l.touch(now);
        images.flush();
        return view(l);
    }

    /** Removing an image does not need review: it only hides content that was already approved. */
    @Transactional
    public ListingView removeImage(UUID ownerId, UUID listingId, UUID imageId) {
        Listing l = lockOwned(ownerId, listingId);
        if (l.getStatus() == SUSPENDED) {
            throw ApiException.invalidState("Listing đã bị đình chỉ.");
        }
        ListingImage img = images.findById(imageId)
                .filter(i -> i.getListingId().equals(listingId))
                .orElseThrow(() -> ApiException.notFound("Ảnh"));
        images.delete(img);
        l.touch(clock.instant());
        images.flush();
        return view(l);
    }

    /** Image bytes of a listing; callers check that the listing is visible to the requester. */
    @Transactional(readOnly = true)
    public ImageData image(UUID listingId, UUID imageId) {
        return images.findById(imageId)
                .filter(i -> i.getListingId().equals(listingId))
                .map(i -> new ImageData(i.getContentType(), i.getData()))
                .orElseThrow(() -> ApiException.notFound("Ảnh"));
    }

    public record ImageData(String contentType, byte[] data) {}

    static String detectImageType(byte[] d) {
        if (d.length >= 3 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8 && (d[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (d.length >= 8 && (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G'
                && d[4] == 0x0D && d[5] == 0x0A && d[6] == 0x1A && d[7] == 0x0A) {
            return "image/png";
        }
        if (d.length >= 12 && d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F'
                && d[8] == 'W' && d[9] == 'E' && d[10] == 'B' && d[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    private ListingView view(Listing l) {
        return views(List.of(l)).get(0);
    }

    /** One query for the image ids of all given listings. */
    private List<ListingView> views(List<Listing> ls) {
        if (ls.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<UUID>> byListing = new HashMap<>();
        for (ListingImageRepository.ImageRef r : images.findRefs(ls.stream().map(Listing::getId).toList())) {
            byListing.computeIfAbsent(r.getListingId(), k -> new ArrayList<>()).add(r.getId());
        }
        Map<UUID, List<GameRiskView>> risks = catalog.listingRisks(ls.stream().map(Listing::getId).toList());
        return ls.stream().map(l -> ListingView.of(l, byListing.get(l.getId()), risks.get(l.getId()))).toList();
    }

    /**
     * Public search (UC-02): ACTIVE listing, ALLOWED game, account that accepts orders; when from/to is given the
     * slot must be free (NOT EXISTS on an overlapping active order — same predicate as the exclusion constraint).
     * The requested slot is extended by the listing's own grace buffer ({@link EndMode#bufferMinutes}), exactly like
     * the block_end_at the new order would get, so a listing shown here can really be booked for [from, to).
     */
    @Transactional(readOnly = true)
    public PageResponse<ListingView> search(UUID gameId, Long minPrice, Long maxPrice, Instant from, Instant to,
                                            int page, int size) {
        if ((from == null) != (to == null) || (from != null && !from.isBefore(to))) {
            throw ApiException.badRequest("VALIDATION_ERROR", "Cần cả from và to, với from < to.");
        }
        StringBuilder where = new StringBuilder("""
                 from listings l
                 join games g on g.id = l.game_id and g.tos_status = 'ALLOWED'
                 join game_accounts a on a.id = l.game_account_id and a.security_state in ('AVAILABLE','IN_USE')
                where l.status = 'ACTIVE'""");
        MapSqlParameterSource p = new MapSqlParameterSource();
        if (gameId != null) {
            where.append(" and l.game_id = :gameId");
            p.addValue("gameId", gameId);
        }
        if (minPrice != null) {
            where.append(" and l.price_per_hour >= :minPrice");
            p.addValue("minPrice", minPrice);
        }
        if (maxPrice != null) {
            where.append(" and l.price_per_hour <= :maxPrice");
            p.addValue("maxPrice", maxPrice);
        }
        if (from != null) {
            // Leading space: a text block strips the indentation, and this follows ":gameId"/":maxPrice" directly.
            where.append(" ").append("""
                     and not exists (select 1 from orders o where o.game_account_id = l.game_account_id
                        and o.status in ('PENDING_PAYMENT','AWAITING_OWNER_APPROVAL','CONFIRMED','IN_RENTAL')
                        and o.rental_period && tstzrange(cast(:from as timestamptz), cast(:to as timestamptz)
                            + make_interval(mins => case when l.end_mode = 'FORCE_AT_END' then 0 else g.grace_minutes end),
                            '[)'))
                     and not exists (select 1 from account_blocks b where b.game_account_id = l.game_account_id
                        and b.period && tstzrange(cast(:from as timestamptz), cast(:to as timestamptz)
                            + make_interval(mins => case when l.end_mode = 'FORCE_AT_END' then 0 else g.grace_minutes end),
                            '[)'))""");
            p.addValue("from", Timestamp.from(from));
            p.addValue("to", Timestamp.from(to));
        }
        Long total = jdbc.queryForObject("select count(*)" + where, p, Long.class);
        p.addValue("limit", size);
        p.addValue("offset", (long) page * size);
        List<UUID> ids = jdbc.queryForList("select l.id" + where + " order by l.price_per_hour, l.created_at limit :limit offset :offset",
                p, UUID.class);
        Map<UUID, Listing> byId = new HashMap<>();
        listings.findAllById(ids).forEach(l -> byId.put(l.getId(), l));
        List<ListingView> content = views(ids.stream().map(byId::get).filter(Objects::nonNull).toList());
        return PageResponse.of(content, page, size, total == null ? 0 : total);
    }

    /**
     * Busy intervals (start, block_end) of a listing's account from now on; times only, no renter data and no owner
     * note. {@code kind}: BOOKED (an order) or OWNER_BUSY (time the owner marked as busy).
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> busyPeriods(UUID gameAccountId) {
        return jdbc.queryForList("""
                select start_at as "startAt", upper(rental_period) as "endAt", 'BOOKED' as "kind" from orders
                 where game_account_id = :id and upper(rental_period) > :now
                   and status in ('PENDING_PAYMENT','AWAITING_OWNER_APPROVAL','CONFIRMED','IN_RENTAL')
                union all
                select start_at, end_at, 'OWNER_BUSY' from account_blocks
                 where game_account_id = :id and end_at > :now
                 order by 1""", new MapSqlParameterSource("id", gameAccountId)
                .addValue("now", Timestamp.from(clock.instant())));
    }

    /** Start of the account's first owner busy time at or after {@code after}, or null. Limits extensions. */
    @Transactional(readOnly = true)
    public Instant nextOwnerBusyStart(UUID gameAccountId, Instant after) {
        Timestamp t = jdbc.queryForObject(
                "select min(start_at) from account_blocks where game_account_id = :id and start_at >= :after",
                new MapSqlParameterSource("id", gameAccountId).addValue("after", Timestamp.from(after)), Timestamp.class);
        return t == null ? null : t.toInstant();
    }

    /**
     * Validates the content and returns it with the per-risk terms applied: when the game has OWNER risks, the
     * listing deposit comes from the owner's deposits for the ones they chose to cover (at least one, since orders
     * always carry a deposit). A risk left without an amount is not covered: no deposit / no compensation for it.
     */
    private Content validateContent(GameView game, Content c) {
        c = applyRiskTerms(game, c);
        if (c.depositAmount() <= 0 || c.depositAmount() < game.depositMin() || c.depositAmount() > game.depositMax()) {
            throw new ApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "DEPOSIT_OUT_OF_RANGE",
                    game.hasOwnerRisks()
                            ? "Tiền cọc của đơn (mức cao nhất người thuê có thể phải trả) phải nằm trong khoảng cho phép của game."
                            : "Tiền cọc phải nằm trong khoảng cho phép của game.",
                    Map.of("depositMin", game.depositMin(), "depositMax", game.depositMax(),
                            "depositAmount", c.depositAmount()));
        }
        if (c.autoAccept() && game.handoverMode() == HandoverMode.OWNER_ASSISTED_OTP) {
            throw ApiException.unprocessable("AUTO_ACCEPT_NOT_ALLOWED",
                    "Game yêu cầu chủ account hỗ trợ OTP nên không thể bật tự động nhận đơn.");
        }
        if (c.pricingTiers().isEmpty() && c.pricePerHour() <= 0) {
            throw ApiException.badRequest("VALIDATION_ERROR", "Cần ít nhất một gói thuê (hoặc giá theo giờ > 0).");
        }
        validateTiers(game, c.pricingTiers());
        Integer freeCancel = c.renterFreeCancelMinutes();
        if (freeCancel != null && (freeCancel < 0 || freeCancel > Content.MAX_FREE_CANCEL_MINUTES)) {
            throw ApiException.badRequest("VALIDATION_ERROR",
                    "Thời gian hủy miễn phí phải từ 0 đến " + Content.MAX_FREE_CANCEL_MINUTES / 60 + " giờ trước giờ bắt đầu.");
        }
        return c;
    }

    private static Content applyRiskTerms(GameView game, Content c) {
        if (game.risks().isEmpty()) {
            return c.withRisks(c.depositAmount(), List.of());
        }
        Map<UUID, RiskTerm> byRisk = new HashMap<>();
        for (RiskTerm t : c.riskTerms()) {
            if (t == null || t.riskId() == null) {
                continue;
            }
            if (game.risk(t.riskId()) == null) {
                throw ApiException.unprocessable("UNKNOWN_RISK", "Rủi ro không thuộc game này hoặc đã bị gỡ, hãy tải lại trang.");
            }
            if (t.amount() != null && t.amount() < 0) {
                throw ApiException.badRequest("VALIDATION_ERROR", "Số tiền cọc / đền bù không được âm.");
            }
            byRisk.put(t.riskId(), t);
        }
        if (!game.hasOwnerRisks()) {
            // Only renter risks: deposit stays the single amount the owner typed.
            return c.withRisks(c.depositAmount(), RiskTerm.normalized(List.copyOf(byRisk.values())));
        }
        long stackedSum = 0;
        long exclusiveMax = 0;
        List<RiskTerm> terms = new ArrayList<>();
        for (GameRiskView r : game.risks()) {
            RiskTerm t = byRisk.get(r.id());
            Long amount = t == null ? null : t.amount();
            if (r.side() == RiskSide.OWNER && amount != null && amount > 0) {
                if (r.stackable()) {
                    stackedSum = Math.addExact(stackedSum, amount);
                } else {
                    exclusiveMax = Math.max(exclusiveMax, amount);
                }
            }
            if (amount != null && amount > 0) {
                terms.add(new RiskTerm(r.id(), amount));
            }
        }
        if (stackedSum == 0 && exclusiveMax == 0) {
            // Owner opted out of every owner risk, but an order always carries a deposit (game minimum > 0).
            throw new ApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                    "RISK_DEPOSIT_REQUIRED", "Cần đặt cọc cho ít nhất một rủi ro của bạn (game yêu cầu tiền cọc tối thiểu).",
                    Map.of("depositMin", game.depositMin()));
        }
        // The deposit covers the worst case: every stackable risk at once, or the largest exclusive one alone.
        return c.withRisks(Math.max(stackedSum, exclusiveMax), RiskTerm.normalized(terms));
    }

    private static void validateTiers(GameView game, List<PricingTier> tiers) {
        if (tiers.size() > PricingTier.MAX_TIERS) {
            throw ApiException.unprocessable("TOO_MANY_PRICING_TIERS", "Mỗi listing tối đa " + PricingTier.MAX_TIERS + " gói thuê.");
        }
        Set<Integer> seen = new HashSet<>();
        for (PricingTier t : tiers) {
            if (t.price() <= 0) {
                throw ApiException.badRequest("VALIDATION_ERROR", "Giá của mỗi gói thuê phải > 0.");
            }
            if (t.durationMinutes() < game.minDurationMinutes() || t.durationMinutes() > game.maxDurationMinutes()) {
                throw new ApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "DURATION_OUT_OF_RANGE",
                        "Thời lượng gói thuê nằm ngoài khoảng cho phép của game.",
                        Map.of("min", game.minDurationMinutes(), "max", game.maxDurationMinutes()));
            }
            if (!seen.add(t.durationMinutes())) {
                throw ApiException.unprocessable("DUPLICATE_PRICING_TIER", "Có hai gói thuê cùng thời lượng.");
            }
        }
    }

    private Listing lockOwned(UUID ownerId, UUID listingId) {
        Listing l = listings.findForUpdate(listingId).orElseThrow(() -> ApiException.notFound("Listing"));
        if (!l.getOwnerId().equals(ownerId)) {
            throw ApiException.notFound("Listing");
        }
        return l;
    }

    private void move(Listing l, ListingStatus to, String note) {
        ListingStatus from = l.getStatus();
        l.moveTo(to, note, clock.instant());
        Map<String, Object> payload = new HashMap<>();
        payload.put("listingId", l.getId());
        payload.put("ownerId", l.getOwnerId());
        payload.put("from", from);
        payload.put("to", to);
        if (note != null) {
            payload.put("note", note);
        }
        outbox.record("Listing", l.getId(), "ListingStatusChanged", payload);
        if (to == PENDING_REVIEW) {
            // Tells admins there is a listing in the review queue.
            outbox.record("Listing", l.getId(), "ListingSubmittedForReview",
                    Map.of("listingId", l.getId(), "gameAccountId", l.getGameAccountId(), "from", from));
        }
    }
}
