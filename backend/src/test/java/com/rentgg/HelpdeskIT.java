package com.rentgg;

import com.rentgg.helpdesk.application.HelpdeskService;
import com.rentgg.helpdesk.application.TicketViews.MessageView;
import com.rentgg.helpdesk.application.TicketViews.TicketView;
import com.rentgg.helpdesk.domain.MessageKind;
import com.rentgg.helpdesk.domain.ParticipantRole;
import com.rentgg.helpdesk.domain.TicketCategory;
import com.rentgg.helpdesk.domain.TicketStatus;
import com.rentgg.order.application.OrderView;
import com.rentgg.support.IntegrationTest;
import com.rentgg.support.IntegrationTest.Setup;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Help desk chat: user ↔ staff, inviting the other side of an order to mediate, visibility and permissions. */
class HelpdeskIT extends IntegrationTest {

    @Autowired HelpdeskService helpdesk;

    private int notifications(UUID user, String type) {
        jobs.publishOutbox();
        Integer n = jdbc.queryForObject("select count(*) from notifications where user_id = ? and type = ?",
                Integer.class, user, type);
        return n == null ? 0 : n;
    }

    @Test
    void userAsksStaffReplies_statusFollowsTheConversation() {
        UUID user = user();
        UUID staff = user("SUPPORT");
        TicketView t = helpdesk.open(user, "Không nạp được tiền", TicketCategory.SUPPORT, null, "Mình nạp mà chưa thấy");
        assertThat(t.status()).isEqualTo(TicketStatus.OPEN);
        assertThat(t.myRole()).isEqualTo(ParticipantRole.REQUESTER);
        jobs.publishOutbox();
        assertThat(jdbc.queryForObject("select count(*) from notifications where user_id = ? and type = ? and data->>'ticketId' = ?",
                Integer.class, staff, "SupportTicketOpened", t.id().toString())).isEqualTo(1);
        assertThat(helpdesk.unread(staff).awaitingStaff()).isGreaterThanOrEqualTo(1);

        clock.advance(Duration.ofMinutes(1));
        helpdesk.post(staff, t.id(), "Bạn gửi giúp mã giao dịch nhé", null);
        TicketView afterReply = helpdesk.get(user, t.id());
        assertThat(afterReply.status()).isEqualTo(TicketStatus.WAITING_USER);
        assertThat(afterReply.assigneeId()).isEqualTo(staff);
        assertThat(afterReply.unread()).isEqualTo(2); // "staff joined" + reply
        assertThat(helpdesk.unread(user).unreadTickets()).isEqualTo(1);
        assertThat(notifications(user, "SupportMessagePosted")).isEqualTo(1);

        // A second staff message before the user reads does not ping again.
        clock.advance(Duration.ofMinutes(1));
        helpdesk.post(staff, t.id(), "Hoặc ảnh chụp màn hình", null);
        assertThat(notifications(user, "SupportMessagePosted")).isEqualTo(1);

        helpdesk.markRead(user, t.id());
        assertThat(helpdesk.unread(user).unreadTickets()).isZero();
        clock.advance(Duration.ofMinutes(1));
        helpdesk.post(user, t.id(), "Mã GD123", null);
        assertThat(helpdesk.get(user, t.id()).status()).isEqualTo(TicketStatus.OPEN);

        helpdesk.changeStatus(user, t.id(), TicketStatus.RESOLVED, null);
        assertApi(() -> helpdesk.changeStatus(user, t.id(), TicketStatus.CLOSED, null), "FORBIDDEN");
        helpdesk.changeStatus(staff, t.id(), TicketStatus.CLOSED, "Đã xử lý");
        assertApi(() -> helpdesk.post(user, t.id(), "Còn nữa", null), "INVALID_STATE");

        List<MessageView> msgs = helpdesk.messages(user, t.id(), null);
        assertThat(msgs).extracting(MessageView::kind).contains(MessageKind.SYSTEM, MessageKind.TEXT);
        assertThat(msgs.get(0).body()).isEqualTo("Mình nạp mà chưa thấy");
    }

    @Test
    void staffInvitesOtherPartyOfOrder_whoOnlySeesMessagesAfterJoining() {
        Setup s = standardListing();
        UUID renter = user();
        OrderView o = paidOrder(renter, s, Duration.ofHours(1), 120);
        UUID staff = user("SUPPORT");
        UUID stranger = user();

        TicketView t = helpdesk.open(renter, "Account bị đổi mật khẩu", TicketCategory.COMPLAINT, o.id(),
                "Chủ account đổi mật khẩu giữa chừng, bí mật: tôi nghi ngờ");
        assertApi(() -> helpdesk.open(stranger, "x", TicketCategory.COMPLAINT, o.id(), "y"), "NOT_FOUND");
        assertApi(() -> helpdesk.get(stranger, t.id()), "NOT_FOUND");
        assertApi(() -> helpdesk.get(s.ownerId(), t.id()), "NOT_FOUND");
        assertApi(() -> helpdesk.addParticipant(renter, t.id(), s.ownerId()), "FORBIDDEN");

        clock.advance(Duration.ofMinutes(1));
        TicketView staffView = helpdesk.addParticipant(staff, t.id(), s.ownerId());
        assertThat(staffView.orderParties().ownerId()).isEqualTo(s.ownerId());
        assertThat(staffView.participants()).extracting(p -> p.role())
                .containsExactlyInAnyOrder(ParticipantRole.REQUESTER, ParticipantRole.STAFF, ParticipantRole.PARTY);
        assertThat(notifications(s.ownerId(), "SupportParticipantAdded")).isEqualTo(1);
        assertApi(() -> helpdesk.addParticipant(staff, t.id(), s.ownerId()), "ALREADY_PARTICIPANT");

        clock.advance(Duration.ofMinutes(1));
        helpdesk.post(s.ownerId(), t.id(), "Tôi chỉ đổi sau khi hết giờ", null);
        List<MessageView> ownerSees = helpdesk.messages(s.ownerId(), t.id(), null);
        assertThat(ownerSees).extracting(MessageView::body).noneMatch(b -> b.contains("bí mật"));
        assertThat(helpdesk.messages(renter, t.id(), null)).extracting(MessageView::body)
                .anyMatch(b -> b.contains("bí mật")).anyMatch(b -> b.contains("hết giờ"));
        assertThat(helpdesk.forOrder(s.ownerId(), o.id())).hasSize(1);
        assertThat(helpdesk.forOrder(stranger, o.id())).isEmpty();

        assertApi(() -> helpdesk.removeParticipant(staff, t.id(), renter), "CANNOT_REMOVE_REQUESTER");
        helpdesk.removeParticipant(staff, t.id(), s.ownerId());
        assertApi(() -> helpdesk.post(s.ownerId(), t.id(), "?", null), "NOT_FOUND");
        assertThat(helpdesk.mine(s.ownerId(), PageRequest.of(0, 20)).getContent()).isEmpty();
    }

    @Test
    void staffOpensMediationWithBothSides() {
        Setup s = standardListing();
        UUID renter = user();
        OrderView o = paidOrder(renter, s, Duration.ofHours(1), 120);
        UUID staff = user("SUPPORT");
        TicketView t = helpdesk.openByStaff(staff, "Hoà giải đơn", null, o.id(), null,
                List.of(renter, s.ownerId()), "Chào hai bạn, mình sẽ hỗ trợ giải quyết.");
        assertThat(t.category()).isEqualTo(TicketCategory.MEDIATION);
        assertThat(t.status()).isEqualTo(TicketStatus.WAITING_USER);
        assertThat(helpdesk.messages(renter, t.id(), null)).extracting(MessageView::body)
                .contains("Chào hai bạn, mình sẽ hỗ trợ giải quyết.");
        assertThat(helpdesk.queue(staff, Set.of(TicketStatus.WAITING_USER), TicketCategory.MEDIATION, true, false,
                "hoà giải", PageRequest.of(0, 20)).getContent()).extracting(TicketView::id).contains(t.id());
        assertApi(() -> helpdesk.openByStaff(renter, "x", null, o.id(), null, List.of(s.ownerId()), "y"), "FORBIDDEN");

        // No one picked: both sides of the order are invited.
        TicketView both = helpdesk.openByStaff(staff, "Hoà giải 2", null, o.id(), null, List.of(), "Chào");
        assertThat(both.participants()).extracting(p -> p.userId()).containsExactlyInAnyOrder(staff, renter, s.ownerId());
    }

    @Test
    void endpointsCheckPermissionAndImagesAreValidated() throws Exception {
        UUID user = user();
        UUID staff = user("SUPPORT");
        mvc.perform(get("/api/v1/support/tickets").header("Authorization", bearer(user)))
                .andExpect(status().isForbidden());
        String body = mvc.perform(post("/api/v1/tickets").header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"Hỏi phí\",\"category\":\"QUESTION\",\"body\":\"Phí bao nhiêu?\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
        mvc.perform(get("/api/v1/support/tickets").header("Authorization", bearer(staff)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == '" + id + "')]").exists());
        mvc.perform(get("/api/v1/tickets/" + id + "/messages").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].body").value("Phí bao nhiêu?"));

        UUID ticket = UUID.fromString(id);
        assertApi(() -> helpdesk.post(user, ticket, null, "not an image".getBytes()), "UNSUPPORTED_MEDIA_TYPE");
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
        MessageView m = helpdesk.post(user, ticket, "", png);
        assertThat(m.attachmentType()).isEqualTo("image/png");
        assertThat(helpdesk.attachment(staff, ticket, m.attachmentId()).data()).isEqualTo(png);
        assertApi(() -> helpdesk.attachment(user(), ticket, m.attachmentId()), "NOT_FOUND");
    }
}
