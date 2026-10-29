package com.rentgg.helpdesk.infrastructure;

import com.rentgg.helpdesk.domain.TicketAttachment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface TicketAttachmentRepository extends JpaRepository<TicketAttachment, UUID> {

    @Query("select count(a) from TicketAttachment a where a.ticketId = :ticketId")
    long countByTicket(@Param("ticketId") UUID ticketId);
}
