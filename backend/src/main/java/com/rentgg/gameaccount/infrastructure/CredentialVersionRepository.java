package com.rentgg.gameaccount.infrastructure;

import com.rentgg.gameaccount.domain.CredentialVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CredentialVersionRepository extends JpaRepository<CredentialVersion, UUID> {

    @Query("select c from CredentialVersion c where c.gameAccountId = :accountId and c.status = 'ACTIVE'")
    Optional<CredentialVersion> findActive(@Param("accountId") UUID accountId);

    List<CredentialVersion> findByGameAccountIdOrderByVersion(UUID gameAccountId);

    @Query("select coalesce(max(c.version), 0) from CredentialVersion c where c.gameAccountId = :accountId")
    int maxVersion(@Param("accountId") UUID accountId);
}
