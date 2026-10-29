package com.rentgg.helpdesk.infrastructure;

import com.rentgg.helpdesk.domain.TicketMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface TicketMessageRepository extends JpaRepository<TicketMessage, UUID> {

    /** Messages created at or after {@code from}, oldest first. */
    @Query("""
            select m from TicketMessage m
             where m.ticketId = :ticketId and m.createdAt >= :from
             order by m.createdAt, m.id""")
    List<TicketMessage> findVisible(@Param("ticketId") UUID ticketId, @Param("from") Instant from);
}
