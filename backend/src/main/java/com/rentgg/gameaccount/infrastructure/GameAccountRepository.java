package com.rentgg.gameaccount.infrastructure;

import com.rentgg.gameaccount.domain.GameAccount;
import com.rentgg.gameaccount.domain.SecurityState;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GameAccountRepository extends JpaRepository<GameAccount, UUID> {

    List<GameAccount> findByOwnerIdOrderByCreatedAtDesc(UUID ownerId);

    List<GameAccount> findBySecurityStateOrderByCreatedAt(SecurityState state);

    Page<GameAccount> findBySecurityState(SecurityState state, Pageable pageable);

    Page<GameAccount> findByOwnerId(UUID ownerId, Pageable pageable);

    Page<GameAccount> findByOwnerIdAndIdIn(UUID ownerId, java.util.Collection<UUID> ids, Pageable pageable);

    /** Owner's accounts that have no listing yet (each account can be listed once). */
    @Query("select a from GameAccount a where a.ownerId = :ownerId"
            + " and not exists (select 1 from Listing l where l.gameAccountId = a.id)")
    Page<GameAccount> findUnlistedByOwner(@Param("ownerId") UUID ownerId, Pageable pageable);

    Optional<GameAccount> findByGameIdAndLoginFingerprint(UUID gameId, String loginFingerprint);

    Optional<GameAccount> findByGameIdAndAccountKeyScopeAndAccountKey(UUID gameId, String accountKeyScope,
                                                                     String accountKey);

    List<GameAccount> findByLoginFingerprintIsNullOrderByCreatedAt();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from GameAccount a where a.id = :id")
    Optional<GameAccount> findForUpdate(@Param("id") UUID id);

    @Query(value = """
            select id from game_accounts where security_state = 'ROTATION_PENDING' and rotation_due_at <= :now
             order by rotation_due_at limit :limit""", nativeQuery = true)
    List<UUID> findRotationOverdueCandidates(@Param("now") Instant now, @Param("limit") int limit);

    @Query(value = "select * from game_accounts where id = :id for update skip locked", nativeQuery = true)
    Optional<GameAccount> lockForJob(@Param("id") UUID id);
}
