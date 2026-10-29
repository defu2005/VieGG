package com.rentgg.listing.domain;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The owner's amount for one risk of the game: the deposit for an OWNER risk, the compensation for a RENTER risk.
 * {@code amount} null on a RENTER risk = no compensation, the renter only gets the rent back.
 */
public record RiskTerm(UUID riskId, Long amount) {

    public static List<RiskTerm> normalized(List<RiskTerm> terms) {
        if (terms == null) {
            return List.of();
        }
        return terms.stream().filter(t -> t != null && t.riskId() != null)
                .map(t -> new RiskTerm(t.riskId(), t.amount() != null && t.amount() == 0 ? null : t.amount()))
                .sorted(Comparator.comparing(t -> t.riskId().toString()))
                .toList();
    }

    static List<RiskTerm> fromJson(List<Map<String, Object>> json) {
        if (json == null) {
            return List.of();
        }
        return json.stream().map(m -> new RiskTerm(UUID.fromString((String) m.get("riskId")),
                m.get("amount") == null ? null : ((Number) m.get("amount")).longValue())).toList();
    }

    static List<Map<String, Object>> toJson(List<RiskTerm> terms) {
        return terms.stream().<Map<String, Object>>map(t -> {
            Map<String, Object> m = new HashMap<>();
            m.put("riskId", t.riskId().toString());
            m.put("amount", t.amount());
            return m;
        }).toList();
    }
}
