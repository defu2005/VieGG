package com.rentgg.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Per-game policy (§5.2). Each field maps to a question of the Validation Checklist part A. */
@Entity
@Table(name = "games")
public class Game {

    @Id
    private UUID id;
    private String code;
    private String name;
    private String publisher;
    @Enumerated(EnumType.STRING)
    private TosStatus tosStatus;
    private String tosUrl;
    private Instant tosReviewedAt;
    @Enumerated(EnumType.STRING)
    private HandoverMode handoverMode;
    private boolean passwordChangeKicksSessions;
    private long depositMin;
    private long depositMax;
    private int depositHoldHours;
    private int sellerHoldHours;
    private int sellerHoldHoursHighRisk;
    private int complaintWindowMinutes;
    private int minDurationMinutes;
    private int maxDurationMinutes;
    private int graceMinutes;
    private int platformFeeBps;
    private int renterFreeCancelMinutes;
    private int lateCancelRefundBps;
    private int ownerFreeCancelHours;
    private int minAge;
    private String riskNotes;
    @Enumerated(EnumType.STRING)
    private GameRiskLevel riskLevel = GameRiskLevel.MEDIUM;
    @Enumerated(EnumType.STRING)
    private AccountKeyType accountKeyType = AccountKeyType.LOGIN;
    private String accountKeyLabel;
    private String accountKeyExample;
    private boolean accountKeyPerServer;
    /** Rental packages staff suggest to owners, see {@link SuggestedPackage}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<Map<String, Object>> suggestedPackages = new ArrayList<>();
    @Version
    private Long version;
    private Instant createdAt;
    private Instant updatedAt;

    protected Game() {}

    public Game(String code, String name, Instant now) {
        this.id = UUID.randomUUID();
        this.code = code;
        this.name = name;
        this.tosStatus = TosStatus.REVIEW_REQUIRED;
        this.handoverMode = HandoverMode.STATIC_CREDENTIAL;
        this.depositHoldHours = 72;
        this.sellerHoldHours = 72;
        this.sellerHoldHoursHighRisk = 168;
        this.complaintWindowMinutes = 15;
        this.minDurationMinutes = 60;
        this.maxDurationMinutes = 1440;
        this.graceMinutes = 10;
        this.platformFeeBps = 1000;
        this.renterFreeCancelMinutes = 120;
        this.lateCancelRefundBps = 5000;
        this.ownerFreeCancelHours = 12;
        this.minAge = 18;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void apply(GamePolicy p, Instant now) {
        this.name = p.name();
        this.publisher = p.publisher();
        this.tosUrl = p.tosUrl();
        this.handoverMode = p.handoverMode();
        this.passwordChangeKicksSessions = p.passwordChangeKicksSessions();
        this.depositMin = p.depositMin();
        this.depositMax = p.depositMax();
        this.depositHoldHours = p.depositHoldHours();
        this.sellerHoldHours = p.sellerHoldHours();
        this.sellerHoldHoursHighRisk = p.sellerHoldHoursHighRisk();
        this.complaintWindowMinutes = p.complaintWindowMinutes();
        this.minDurationMinutes = p.minDurationMinutes();
        this.maxDurationMinutes = p.maxDurationMinutes();
        this.graceMinutes = p.graceMinutes();
        this.platformFeeBps = p.platformFeeBps();
        this.renterFreeCancelMinutes = p.renterFreeCancelMinutes();
        this.lateCancelRefundBps = p.lateCancelRefundBps();
        this.ownerFreeCancelHours = p.ownerFreeCancelHours();
        this.minAge = p.minAge();
        this.riskNotes = p.riskNotes();
        // Older clients do not send a level: keep the current one instead of resetting it.
        if (p.riskLevel() != null) this.riskLevel = p.riskLevel();
        if (p.accountKey() != null) {
            this.accountKeyType = p.accountKey().type();
            this.accountKeyLabel = p.accountKey().label();
            this.accountKeyExample = p.accountKey().example();
            this.accountKeyPerServer = p.accountKey().perServer();
        }
        this.updatedAt = now;
    }

    public AccountKeyRule getAccountKeyRule() {
        return new AccountKeyRule(accountKeyType, accountKeyLabel, accountKeyExample, accountKeyPerServer);
    }

    public void replaceSuggestedPackages(List<SuggestedPackage> packages, Instant now) {
        List<SuggestedPackage> sorted = new ArrayList<>(packages);
        sorted.sort(Comparator.comparingInt(SuggestedPackage::durationMinutes));
        this.suggestedPackages = new ArrayList<>(SuggestedPackage.toJson(sorted));
        this.updatedAt = now;
    }

    public List<SuggestedPackage> getSuggestedPackages() { return SuggestedPackage.fromJson(suggestedPackages); }

    public void changeTosStatus(TosStatus status, Instant now) {
        this.tosStatus = status;
        this.tosReviewedAt = now;
        this.updatedAt = now;
    }

    public void setTosUrl(String tosUrl) {
        if (tosUrl != null && !tosUrl.isBlank()) {
            this.tosUrl = tosUrl;
        }
    }

    public UUID getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getPublisher() { return publisher; }
    public TosStatus getTosStatus() { return tosStatus; }
    public String getTosUrl() { return tosUrl; }
    public Instant getTosReviewedAt() { return tosReviewedAt; }
    public HandoverMode getHandoverMode() { return handoverMode; }
    public boolean isPasswordChangeKicksSessions() { return passwordChangeKicksSessions; }
    public long getDepositMin() { return depositMin; }
    public long getDepositMax() { return depositMax; }
    public int getDepositHoldHours() { return depositHoldHours; }
    public int getSellerHoldHours() { return sellerHoldHours; }
    public int getSellerHoldHoursHighRisk() { return sellerHoldHoursHighRisk; }
    public int getComplaintWindowMinutes() { return complaintWindowMinutes; }
    public int getMinDurationMinutes() { return minDurationMinutes; }
    public int getMaxDurationMinutes() { return maxDurationMinutes; }
    public int getGraceMinutes() { return graceMinutes; }
    public int getPlatformFeeBps() { return platformFeeBps; }
    public int getRenterFreeCancelMinutes() { return renterFreeCancelMinutes; }
    public int getLateCancelRefundBps() { return lateCancelRefundBps; }
    public int getOwnerFreeCancelHours() { return ownerFreeCancelHours; }
    public int getMinAge() { return minAge; }
    public String getRiskNotes() { return riskNotes; }
    public GameRiskLevel getRiskLevel() { return riskLevel; }
}
