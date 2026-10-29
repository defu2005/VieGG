package com.rentgg.identity.application;

import com.rentgg.identity.domain.KycStatus;
import com.rentgg.identity.domain.RiskTier;
import com.rentgg.identity.domain.User;
import com.rentgg.identity.domain.UserStatus;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/** Read model other modules use instead of the User entity (§4.4 rule 2). */
public record UserView(UUID id, String email, String displayName, LocalDate dateOfBirth, UserStatus status,
                       KycStatus kycStatus, RiskTier riskTier, Set<String> roles) {

    static UserView of(User u) {
        return new UserView(u.getId(), u.getEmail(), u.getDisplayName(), u.getDateOfBirth(), u.getStatus(),
                u.getKycStatus(), u.getRiskTier(), Set.copyOf(u.getRoles()));
    }

    public boolean highRisk() {
        return riskTier == RiskTier.HIGH;
    }
}
