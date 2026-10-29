package com.rentgg.dispute.infrastructure;

import com.rentgg.dispute.domain.Dispute;
import com.rentgg.dispute.domain.DisputeStatus;
import com.rentgg.dispute.domain.DisputeType;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DisputeRepository extends JpaRepository<Dispute, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Dispute d where d.id = :id")
    Optional<Dispute> findForUpdate(@Param("id") UUID id);

    List<Dispute> findByOrderIdOrderByCreatedAt(UUID orderId);

    boolean existsByOrderIdAndTypeAndStatus(UUID orderId, DisputeType type, DisputeStatus status);

    @Query("select count(d) > 0 from Dispute d where d.orderId = :orderId and d.status = 'OPEN' and d.type = :type and d.riskId = :riskId")
    boolean existsOpenForRisk(@Param("orderId") UUID orderId, @Param("type") DisputeType type, @Param("riskId") UUID riskId);
    @Query("select count(d) > 0 from Dispute d where d.orderId = :orderId and d.status = 'OPEN' and d.type in :types and d.id <> :exclude")
    boolean existsOpenOfTypesExcept(@Param("orderId") UUID orderId, @Param("types") Collection<DisputeType> types,
                                    @Param("exclude") UUID exclude);

    @Query("select count(d) > 0 from Dispute d where d.orderId = :orderId and d.status = 'OPEN' and d.type in :types")
    boolean existsOpenOfTypes(@Param("orderId") UUID orderId, @Param("types") Collection<DisputeType> types);

    Page<Dispute> findByStatusAndType(DisputeStatus status, DisputeType type, Pageable pageable);

    Page<Dispute> findByStatus(DisputeStatus status, Pageable pageable);

    Page<Dispute> findByType(DisputeType type, Pageable pageable);

    String SEARCH_WHERE = """
              from disputes d
              join orders o on o.id = d.order_id
              join users ru on ru.id = o.renter_id
              join users ou on ou.id = o.owner_id
             where (:status = '' or d.status = :status)
               and (:type = '' or d.type = :type)
               and (d.id::text like :q || '%' or d.order_id::text like :q || '%'
                    or ru.id::text like :q || '%' or ou.id::text like :q || '%'
                    or ru.email like '%' || :q || '%' or ou.email like '%' || :q || '%'
                    or rentgg_fold(ru.display_name) like '%' || rentgg_fold(:q) || '%'
                    or rentgg_fold(ou.display_name) like '%' || rentgg_fold(:q) || '%')
            """;

    /** Free-text support search: dispute / order id prefix, or id prefix, email or name of renter / owner. */
    @Query(value = "select d.id " + SEARCH_WHERE
            + " order by d.deadline_at asc nulls last, d.id limit :limit offset :offset", nativeQuery = true)
    List<UUID> searchIds(@Param("q") String q, @Param("status") String status, @Param("type") String type,
                         @Param("limit") int limit, @Param("offset") long offset);

    @Query(value = "select count(*) " + SEARCH_WHERE, nativeQuery = true)
    long countSearch(@Param("q") String q, @Param("status") String status, @Param("type") String type);
}
