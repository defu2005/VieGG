package com.rentgg.shared.web;

import com.rentgg.shared.error.ApiException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Set;

/** Pagination rules of §11.4: page from 0, size default 20 max 100, sort only on whitelisted fields. */
public final class Paging {

    private Paging() {}

    public static Pageable of(Integer page, Integer size, String sort, Set<String> allowedSortFields, Sort defaultSort) {
        int p = page == null ? 0 : Math.max(0, page);
        int s = size == null ? 20 : Math.min(100, Math.max(1, size));
        Sort srt = defaultSort;
        if (sort != null && !sort.isBlank()) {
            String[] parts = sort.split(",");
            String field = parts[0].trim();
            if (!allowedSortFields.contains(field)) {
                throw ApiException.badRequest("VALIDATION_ERROR", "Không thể sắp xếp theo trường " + field);
            }
            Sort.Direction dir = parts.length > 1 && parts[1].trim().equalsIgnoreCase("asc")
                    ? Sort.Direction.ASC : Sort.Direction.DESC;
            srt = Sort.by(dir, field);
        }
        return PageRequest.of(p, s, srt);
    }
}
