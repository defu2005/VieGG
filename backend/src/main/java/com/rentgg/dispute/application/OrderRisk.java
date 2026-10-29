package com.rentgg.dispute.application;

import com.rentgg.catalog.domain.RiskSide;
import com.rentgg.order.application.OrderView;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One risk as frozen in the order's policy_snapshot ("risks"). {@code amount}: the owner's deposit share (OWNER,
 * null = whole order deposit for listings made before risks) or compensation (RENTER, null = none).
 * {@code stackable} false (OWNER): exclusive, the renter pays only this risk's deposit when it happens.
 * {@code covered} false (OWNER): the owner chose no deposit for this risk, so it cannot be claimed.
 */
record OrderRisk(UUID riskId, RiskSide side, String name, Long amount, Integer complaintWindowMinutes,
                 boolean stackable, boolean covered) {

    static OrderRisk find(OrderView o, UUID riskId) {
        if (riskId == null || !(o.policySnapshot().get("risks") instanceof List<?> list)) {
            return null;
        }
        for (Object item : list) {
            if (item instanceof Map<?, ?> m && riskId.toString().equals(m.get("riskId"))) {
                return new OrderRisk(riskId, RiskSide.valueOf((String) m.get("side")), (String) m.get("name"),
                        m.get("amount") == null ? null : ((Number) m.get("amount")).longValue(),
                        m.get("complaintWindowMinutes") == null ? null : ((Number) m.get("complaintWindowMinutes")).intValue(),
                        // Snapshots taken before V18 have no flag: every risk was stackable then.
                        !Boolean.FALSE.equals(m.get("stackable")),
                        // Snapshots taken before owners could opt out have no flag: every risk was covered.
                        !Boolean.FALSE.equals(m.get("covered")));
            }
        }
        return null;
    }
}
