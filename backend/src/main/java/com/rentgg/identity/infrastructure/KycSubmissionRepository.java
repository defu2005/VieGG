package com.rentgg.identity.infrastructure;

import com.rentgg.identity.domain.KycSubmission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface KycSubmissionRepository extends JpaRepository<KycSubmission, UUID> {

    Optional<KycSubmission> findFirstByUserIdOrderByCreatedAtDesc(UUID userId);
}
