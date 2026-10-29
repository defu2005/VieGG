package com.rentgg.helpdesk.infrastructure;

import com.rentgg.helpdesk.domain.SupportTicket;
import com.rentgg.helpdesk.domain.TicketCategory;
import com.rentgg.helpdesk.domain.TicketStatus;
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

public interface SupportTicketRepository extends JpaRepository<SupportTicket, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from SupportTicket t where t.id = :id")
    Optional<SupportTicket> findForUpdate(@Param("id") UUID id);

    @Query("""
            select t from SupportTicket t
             where t.id in (select p.ticketId from TicketParticipant p where p.userId = :userId and p.leftAt is null)
             order by t.lastMessageAt desc""")
    Page<SupportTicket> findForParticipant(@Param("userId") UUID userId, Pageable pageable);

    /** Staff queue. {@code q} is lower-cased ('' = no text filter); {@code mine} limits to {@code staffId}. */
    @Query("""
            select t from SupportTicket t
             where t.status in :statuses
               and (:category is null or t.category = :category)
               and (:mine = false or t.assigneeId = :staffId)
               and (:unassigned = false or t.assigneeId is null)
               and (:q = '' or lower(t.subject) like concat('%', :q, '%')
                    or lower(coalesce(t.lastMessagePreview, '')) like concat('%', :q, '%'))
             order by t.lastMessageAt desc""")
    Page<SupportTicket> searchQueue(@Param("statuses") Collection<TicketStatus> statuses,
                                    @Param("category") TicketCategory category, @Param("mine") boolean mine,
                                    @Param("staffId") UUID staffId, @Param("unassigned") boolean unassigned,
                                    @Param("q") String q, Pageable pageable);

    List<SupportTicket> findByOrderIdOrderByCreatedAtDesc(UUID orderId);

    @Query("""
            select count(t) from SupportTicket t
             where t.status = com.rentgg.helpdesk.domain.TicketStatus.OPEN
               and (t.assigneeId is null or t.assigneeId = :staffId)""")
    long countAwaitingStaff(@Param("staffId") UUID staffId);
}
