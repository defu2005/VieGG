package com.rentgg.helpdesk.infrastructure;

import com.rentgg.helpdesk.domain.TicketParticipant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TicketParticipantRepository extends JpaRepository<TicketParticipant, UUID> {

    List<TicketParticipant> findByTicketIdOrderByJoinedAt(UUID ticketId);

    Optional<TicketParticipant> findByTicketIdAndUserId(UUID ticketId, UUID userId);
}
