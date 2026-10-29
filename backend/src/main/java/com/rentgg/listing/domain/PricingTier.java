package com.rentgg.listing.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** One rental package of a listing: a fixed duration sold at a fixed price (VND). */
public record PricingTier(int durationMinutes, long price) {

    public static final int MAX_TIERS = 10;

    /** Hourly price shown in search and used for sorting: the shortest package's price per hour, rounded up. */
    public static long hourlyPriceOf(List<PricingTier> tiers) {
        PricingTier shortest = tiers.stream().min(Comparator.comparingInt(PricingTier::durationMinutes)).orElseThrow();
        return Math.max(1, Math.ceilDiv(Math.multiplyExact(shortest.price(), 60L), (long) shortest.durationMinutes()));
    }

    public static List<PricingTier> sorted(List<PricingTier> tiers) {
        if (tiers == null) {
            return List.of();
        }
        List<PricingTier> copy = new ArrayList<>(tiers);
        copy.sort(Comparator.comparingInt(PricingTier::durationMinutes));
        return List.copyOf(copy);
    }

    static List<PricingTier> fromJson(List<Map<String, Object>> json) {
        if (json == null) {
            return List.of();
        }
        return json.stream().map(m -> new PricingTier(((Number) m.get("durationMinutes")).intValue(),
                ((Number) m.get("price")).longValue())).toList();
    }

    static List<Map<String, Object>> toJson(List<PricingTier> tiers) {
        return tiers.stream().<Map<String, Object>>map(t -> Map.of("durationMinutes", t.durationMinutes(), "price", t.price()))
                .toList();
    }
}
