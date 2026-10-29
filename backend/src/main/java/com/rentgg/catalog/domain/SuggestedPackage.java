package com.rentgg.catalog.domain;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A rental package staff suggest for a game. {@code price} null = only the duration is suggested, the owner sets
 * the price. Owners choose which suggested packages a listing offers and may add their own.
 */
public record SuggestedPackage(int durationMinutes, Long price) {

    public static final int MAX_PACKAGES = 10;

    public static List<SuggestedPackage> fromJson(List<Map<String, Object>> json) {
        if (json == null) {
            return List.of();
        }
        return json.stream().map(m -> new SuggestedPackage(((Number) m.get("durationMinutes")).intValue(),
                        m.get("price") == null ? null : ((Number) m.get("price")).longValue()))
                .sorted(Comparator.comparingInt(SuggestedPackage::durationMinutes)).toList();
    }

    public static List<Map<String, Object>> toJson(List<SuggestedPackage> packages) {
        return packages.stream().<Map<String, Object>>map(p -> {
            Map<String, Object> m = new HashMap<>();
            m.put("durationMinutes", p.durationMinutes());
            m.put("price", p.price());
            return m;
        }).toList();
    }
}
