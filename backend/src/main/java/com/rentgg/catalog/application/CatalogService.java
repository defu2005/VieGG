package com.rentgg.catalog.application;

import com.rentgg.audit.application.AuditService;
import com.rentgg.catalog.domain.Game;
import com.rentgg.catalog.domain.GamePolicy;
import com.rentgg.catalog.domain.GameRisk;
import com.rentgg.catalog.domain.RiskSide;
import com.rentgg.catalog.domain.SuggestedPackage;
import com.rentgg.catalog.domain.TosStatus;
import com.rentgg.catalog.infrastructure.GameRepository;
import com.rentgg.catalog.infrastructure.GameRiskRepository;
import com.rentgg.shared.error.ApiException;
import com.rentgg.shared.outbox.OutboxService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class CatalogService {

    public static final int MAX_RISKS = 20;

    private final GameRepository games;
    private final GameRiskRepository risks;
    private final AuditService audit;
    private final OutboxService outbox;
    private final Clock clock;

    public CatalogService(GameRepository games, GameRiskRepository risks, AuditService audit, OutboxService outbox,
                          Clock clock) {
        this.games = games;
        this.risks = risks;
        this.audit = audit;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public GameView get(UUID id) {
        return view(games.findById(id).orElseThrow(() -> ApiException.notFound("Game")));
    }

    @Transactional(readOnly = true)
    public List<GameView> list(boolean includeAll) {
        List<Game> list = includeAll ? games.findAllByOrderByName() : games.findByTosStatusOrderByName(TosStatus.ALLOWED);
        Map<UUID, List<GameRiskView>> byGame = risks.findByGameIdInAndListingIdIsNullAndArchivedAtIsNullOrderByPosition(
                        list.stream().map(Game::getId).toList()).stream()
                .collect(Collectors.groupingBy(GameRisk::getGameId,
                        Collectors.mapping(GameRiskView::of, Collectors.toList())));
        return list.stream().map(g -> GameView.of(g, byGame.getOrDefault(g.getId(), List.of()))).toList();
    }

    /** Fail-closed check used before listing submit, order creation and order start. */
    @Transactional(readOnly = true)
    public GameView requireAllowed(UUID gameId) {
        GameView g = get(gameId);
        if (!g.allowed()) {
            throw ApiException.unprocessable("GAME_NOT_ALLOWED", "Game này hiện không được phép cho thuê.");
        }
        return g;
    }

    /** New games always start as REVIEW_REQUIRED (fail-closed, §14.1). */
    @Transactional
    public GameView create(UUID actorId, String code, GamePolicy policy) {
        if (games.findByCode(code).isPresent()) {
            throw ApiException.conflict("GAME_CODE_TAKEN", "Mã game đã tồn tại.");
        }
        Game g = new Game(code, policy.name(), clock.instant());
        g.apply(policy, clock.instant());
        games.save(g);
        audit.record(actorId, "GAME_CREATED", "Game", g.getId(), null, Map.of("code", code, "tosStatus", "REVIEW_REQUIRED"), null);
        return view(g);
    }

    /** Creates the game and its risks in one transaction ({@code risks} null = none yet). */
    @Transactional
    public GameView create(UUID actorId, String code, GamePolicy policy, List<RiskInput> risks) {
        GameView g = create(actorId, code, policy);
        return risks == null || risks.isEmpty() ? g : replaceRisks(actorId, g.id(), risks);
    }

    /** Policy changes apply to new orders only; existing orders keep their policy_snapshot. */
    @Transactional
    public GameView update(UUID actorId, UUID gameId, GamePolicy policy) {
        Game g = games.findForUpdate(gameId).orElseThrow(() -> ApiException.notFound("Game"));
        Map<String, Object> before = snapshot(g);
        if (policy.accountKey() != null && g.getAccountKeyRule().changesIdentity(policy.accountKey())
                && games.hasAccountKeys(gameId)) {
            throw ApiException.conflict("ACCOUNT_KEY_RULE_LOCKED",
                    "Game đã có account mang định danh theo quy tắc hiện tại, không thể đổi kiểu định danh "
                            + "hoặc phạm vi server. Vẫn có thể sửa nhãn và ví dụ.");
        }
        g.apply(policy, clock.instant());
        audit.record(actorId, "GAME_POLICY_UPDATED", "Game", g.getId(), before, snapshot(g), null);
        return view(g);
    }

    /**
     * One risk as staff edit it; {@code id} null creates a new risk. {@code recommendation}: free-text advice on the
     * deposit / compensation. {@code stackable} (OWNER risks) false = exclusive: when it happens the renter pays only
     * this risk's deposit.
     */
    public record RiskInput(UUID id, RiskSide side, String name, String description, String recommendation,
                            Integer complaintWindowMinutes, boolean stackable) {

        public RiskInput(UUID id, RiskSide side, String name, String description, String recommendation,
                         Integer complaintWindowMinutes) {
            this(id, side, name, description, recommendation, complaintWindowMinutes, true);
        }
    }

    /**
     * Replaces the game's risk list (order = display order). Risks missing from the list are archived, not
     * deleted: listings, order snapshots and disputes keep pointing at them. Existing orders keep their snapshot.
     */
    @Transactional
    public GameView replaceRisks(UUID actorId, UUID gameId, List<RiskInput> input) {
        Game g = games.findForUpdate(gameId).orElseThrow(() -> ApiException.notFound("Game"));
        List<RiskInput> list = input == null ? List.of() : input;
        if (list.size() > MAX_RISKS) {
            throw ApiException.unprocessable("TOO_MANY_RISKS", "Mỗi game tối đa " + MAX_RISKS + " rủi ro.");
        }
        Map<UUID, GameRisk> existing = risks.findByGameIdAndListingIdIsNullOrderByPosition(gameId).stream()
                .collect(Collectors.toMap(GameRisk::getId, Function.identity()));
        List<Map<String, Object>> before = existing.values().stream().filter(GameRisk::isActive)
                .map(CatalogService::riskSnapshot).toList();
        Instant now = clock.instant();
        Set<UUID> kept = new HashSet<>();
        Set<String> names = new HashSet<>();
        int position = 0;
        for (RiskInput in : list) {
            validateRisk(in);
            if (!names.add(in.side() + ":" + in.name().trim().toLowerCase())) {
                throw ApiException.unprocessable("DUPLICATE_RISK", "Rủi ro \"" + in.name().trim() + "\" bị trùng.");
            }
            GameRisk r;
            if (in.id() != null) {
                r = existing.get(in.id());
                if (r == null) {
                    throw ApiException.notFound("Rủi ro");
                }
            } else {
                r = new GameRisk(gameId, now);
            }
            String description = in.description() == null || in.description().isBlank() ? null : in.description().trim();
            String recommendation = in.recommendation() == null || in.recommendation().isBlank()
                    ? null : in.recommendation().trim();
            r.apply(in.side(), in.name().trim(), description, recommendation, in.complaintWindowMinutes(),
                    in.stackable(), position++, now);
            risks.save(r);
            kept.add(r.getId());
        }
        for (GameRisk r : existing.values()) {
            if (!kept.contains(r.getId())) {
                r.archive(now);
            }
        }
        risks.flush();
        GameView after = view(g);
        audit.record(actorId, "GAME_RISKS_UPDATED", "Game", gameId, Map.of("risks", before),
                Map.of("risks", after.risks().stream().map(CatalogService::riskSnapshot).toList()), null);
        return after;
    }

    /** Most risks an owner can add to one listing, on top of the game's own. */
    public static final int MAX_LISTING_RISKS = 10;

    /** Active risks the owners added to these listings, in display order, by listing id. */
    @Transactional(readOnly = true)
    public Map<UUID, List<GameRiskView>> listingRisks(java.util.Collection<UUID> listingIds) {
        if (listingIds == null || listingIds.isEmpty()) {
            return Map.of();
        }
        return risks.findByListingIdInAndArchivedAtIsNullOrderByPosition(listingIds).stream()
                .collect(Collectors.groupingBy(GameRisk::getListingId,
                        Collectors.mapping(GameRiskView::of, Collectors.toList())));
    }

    public List<GameRiskView> listingRisks(UUID listingId) {
        return listingRisks(List.of(listingId)).getOrDefault(listingId, List.of());
    }

    /** Ids of every risk ever added to this listing, archived ones included (an edit may bring one back). */
    @Transactional(readOnly = true)
    public Set<UUID> listingRiskIds(UUID listingId) {
        return risks.findByListingIdOrderByPosition(listingId).stream().map(GameRisk::getId)
                .collect(Collectors.toSet());
    }

    /**
     * Checks the owner's own risks for a listing (same rules as staff risks, at most {@link #MAX_LISTING_RISKS},
     * names unique among themselves and against the game's risks). Staff advice is not used: always null.
     */
    public static void validateListingRisks(GameView game, List<RiskInput> input) {
        if (input.size() > MAX_LISTING_RISKS) {
            throw ApiException.unprocessable("TOO_MANY_RISKS", "Mỗi listing tối đa " + MAX_LISTING_RISKS + " rủi ro tự thêm.");
        }
        Set<String> names = new HashSet<>();
        for (GameRiskView r : game.risks()) {
            names.add(r.side() + ":" + r.name().trim().toLowerCase());
        }
        for (RiskInput in : input) {
            validateRisk(in);
            if (!names.add(in.side() + ":" + in.name().trim().toLowerCase())) {
                throw ApiException.unprocessable("DUPLICATE_RISK", "Rủi ro \"" + in.name().trim() + "\" bị trùng.");
            }
        }
    }

    /**
     * Saves the owner's risks of a listing ({@code input} already checked by {@link #validateListingRisks}, every
     * id set: an existing risk of the listing or a new one). Risks left out are archived, like game risks.
     */
    @Transactional
    public void saveListingRisks(UUID gameId, UUID listingId, List<RiskInput> input) {
        Map<UUID, GameRisk> existing = risks.findByListingIdOrderByPosition(listingId).stream()
                .collect(Collectors.toMap(GameRisk::getId, Function.identity()));
        Instant now = clock.instant();
        Set<UUID> kept = new HashSet<>();
        int position = 0;
        for (RiskInput in : input) {
            GameRisk r = existing.get(in.id());
            if (r == null) {
                if (risks.existsById(in.id())) {
                    throw ApiException.notFound("Rủi ro");
                }
                r = GameRisk.forListing(in.id(), gameId, listingId, now);
            }
            String description = in.description() == null || in.description().isBlank() ? null : in.description().trim();
            r.apply(in.side(), in.name().trim(), description, null, in.complaintWindowMinutes(), in.stackable(),
                    position++, now);
            risks.save(r);
            kept.add(r.getId());
        }
        for (GameRisk r : existing.values()) {
            if (!kept.contains(r.getId())) {
                r.archive(now);
            }
        }
        risks.flush();
    }

    /**
     * Replaces the rental packages suggested to owners of this game. Only a suggestion: existing listings keep their
     * packages, owners pick which ones to offer and can still add their own.
     */
    @Transactional
    public GameView replaceSuggestedPackages(UUID actorId, UUID gameId, List<SuggestedPackage> input) {
        Game g = games.findForUpdate(gameId).orElseThrow(() -> ApiException.notFound("Game"));
        List<SuggestedPackage> list = input == null ? List.of() : input;
        if (list.size() > SuggestedPackage.MAX_PACKAGES) {
            throw ApiException.unprocessable("TOO_MANY_PRICING_TIERS",
                    "Mỗi game tối đa " + SuggestedPackage.MAX_PACKAGES + " gói gợi ý.");
        }
        Set<Integer> seen = new HashSet<>();
        List<SuggestedPackage> clean = new ArrayList<>();
        for (SuggestedPackage p : list) {
            if (p == null) {
                continue;
            }
            if (p.durationMinutes() < g.getMinDurationMinutes() || p.durationMinutes() > g.getMaxDurationMinutes()) {
                throw new ApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "DURATION_OUT_OF_RANGE",
                        "Thời lượng gói gợi ý nằm ngoài khoảng cho phép của game.",
                        Map.of("min", g.getMinDurationMinutes(), "max", g.getMaxDurationMinutes()));
            }
            if (p.price() != null && p.price() < 0) {
                throw ApiException.badRequest("VALIDATION_ERROR", "Giá gợi ý không được âm.");
            }
            if (!seen.add(p.durationMinutes())) {
                throw ApiException.unprocessable("DUPLICATE_PRICING_TIER", "Có hai gói gợi ý cùng thời lượng.");
            }
            clean.add(new SuggestedPackage(p.durationMinutes(), p.price() == null || p.price() == 0 ? null : p.price()));
        }
        List<Map<String, Object>> before = SuggestedPackage.toJson(g.getSuggestedPackages());
        g.replaceSuggestedPackages(clean, clock.instant());
        audit.record(actorId, "GAME_PACKAGES_UPDATED", "Game", gameId, Map.of("packages", before),
                Map.of("packages", SuggestedPackage.toJson(g.getSuggestedPackages())), null);
        return view(g);
    }

    private static void validateRisk(RiskInput in) {
        if (in.side() == null) {
            throw ApiException.badRequest("VALIDATION_ERROR", "Cần chọn rủi ro thuộc về owner hay renter.");
        }
        if (in.name() == null || in.name().isBlank() || in.name().trim().length() > 120) {
            throw ApiException.badRequest("VALIDATION_ERROR", "Tên rủi ro bắt buộc, tối đa 120 ký tự.");
        }
        if (in.description() != null && in.description().length() > 1000) {
            throw ApiException.badRequest("VALIDATION_ERROR", "Mô tả rủi ro tối đa 1000 ký tự.");
        }
        if (in.recommendation() != null && in.recommendation().trim().length() > 500) {
            throw ApiException.badRequest("VALIDATION_ERROR", "Khuyến nghị tối đa 500 ký tự.");
        }
        Integer w = in.complaintWindowMinutes();
        if (w != null && (w < 1 || w > 43_200)) {
            throw ApiException.badRequest("VALIDATION_ERROR", "Hạn khiếu nại phải từ 1 phút đến 30 ngày.");
        }
    }

    /** True if moving to ALLOWED needs a GAME_ALLOW dual approval. */
    @Transactional(readOnly = true)
    public boolean allowRequiresApproval(UUID gameId) {
        TosStatus current = get(gameId).tosStatus();
        return current == TosStatus.PROHIBITED || current == TosStatus.SUSPENDED;
    }

    /**
     * Changes the ToS status. Moving PROHIBITED/SUSPENDED → ALLOWED is only legal when {@code approvedBy}
     * is set by the approval module (§8.10).
     */
    @Transactional
    public GameView changeTosStatus(UUID actorId, UUID gameId, TosStatus target, String tosUrl, String note,
                                    UUID approvedBy) {
        Game g = games.findForUpdate(gameId).orElseThrow(() -> ApiException.notFound("Game"));
        TosStatus before = g.getTosStatus();
        if (target == TosStatus.ALLOWED && (before == TosStatus.PROHIBITED || before == TosStatus.SUSPENDED)
                && approvedBy == null) {
            throw ApiException.forbidden("APPROVAL_REQUIRED", "Cần phê duyệt kép (GAME_ALLOW) để mở lại game này.");
        }
        g.setTosUrl(tosUrl);
        if (before == target) {
            return view(g);
        }
        g.changeTosStatus(target, clock.instant());
        Map<String, Object> after = new HashMap<>();
        after.put("tosStatus", target.name());
        if (note != null) {
            after.put("note", note);
        }
        if (approvedBy != null) {
            after.put("approvedBy", approvedBy.toString());
        }
        audit.record(actorId, "GAME_TOS_STATUS_CHANGED", "Game", gameId, Map.of("tosStatus", before.name()), after, null);
        outbox.record("Game", gameId, "GameTosStatusChanged", Map.of("gameId", gameId, "from", before, "to", target));
        return view(g);
    }

    private GameView view(Game g) {
        List<GameRiskView> active = new ArrayList<>();
        for (GameRisk r : risks.findByGameIdAndListingIdIsNullOrderByPosition(g.getId())) {
            if (r.isActive()) {
                active.add(GameRiskView.of(r));
            }
        }
        return GameView.of(g, active);
    }

    private static Map<String, Object> riskSnapshot(GameRisk r) {
        return riskSnapshot(GameRiskView.of(r));
    }

    private static Map<String, Object> riskSnapshot(GameRiskView r) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", r.id().toString());
        m.put("side", r.side().name());
        m.put("name", r.name());
        m.put("recommendation", r.recommendation());
        m.put("complaintWindowMinutes", r.complaintWindowMinutes());
        m.put("stackable", r.stackable());
        return m;
    }

    private static Map<String, Object> snapshot(Game g) {
        Map<String, Object> m = new HashMap<>();
        m.put("depositMin", g.getDepositMin());
        m.put("depositMax", g.getDepositMax());
        m.put("depositHoldHours", g.getDepositHoldHours());
        m.put("platformFeeBps", g.getPlatformFeeBps());
        m.put("handoverMode", g.getHandoverMode().name());
        m.put("minDurationMinutes", g.getMinDurationMinutes());
        m.put("maxDurationMinutes", g.getMaxDurationMinutes());
        m.put("accountKeyType", g.getAccountKeyRule().type().name());
        m.put("accountKeyPerServer", g.getAccountKeyRule().perServer());
        return m;
    }
}
