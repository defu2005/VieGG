package com.rentgg;

import com.rentgg.dispute.application.DisputeService;
import com.rentgg.dispute.application.DisputeService.Decision;
import com.rentgg.dispute.application.DisputeView;
import com.rentgg.dispute.domain.DisputeType;
import com.rentgg.dispute.domain.EvidenceMedia;
import com.rentgg.order.application.HandoverService;
import com.rentgg.order.application.OrderView;
import com.rentgg.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Photo/video evidence on disputes: upload rules, who can see the files, closed disputes. */
class DisputeAttachmentIT extends IntegrationTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D};
    private static final byte[] MP4 = {0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm', 0, 0, 2, 0};
    private static final byte[] HEIC = {0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c', 0, 0, 0, 0};

    @Autowired DisputeService disputes;
    @Autowired HandoverService credentials;

    private record Case(Setup s, UUID renter, OrderView order, DisputeView dispute) {}

    private Case openRenterDispute() {
        Setup s = standardListing();
        UUID renter = user();
        OrderView o = paidOrder(renter, s, Duration.ofHours(1), 180);
        advanceAndRun(Duration.ofHours(1));
        credentials.viewCredentials(renter, o.id());
        clock.advance(Duration.ofMinutes(5));
        DisputeView d = disputes.open(renter, o.id(), DisputeType.RENTER_LOGIN_FAILED, "wrong password", List.of());
        return new Case(s, renter, o, d);
    }

    private String upload(UUID disputeId, UUID as, String name, byte[] data) throws Exception {
        return mvc.perform(multipart("/api/v1/disputes/" + disputeId + "/attachments")
                        .file(new MockMultipartFile("file", name, "application/octet-stream", data))
                        .param("note", "màn hình lỗi")
                        .header("Authorization", bearer(as)))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void renterUploadsPhotoAndVideo_partiesAndSupportCanView() throws Exception {
        Case c = openRenterDispute();
        UUID id = c.dispute().id();

        mvc.perform(multipart("/api/v1/disputes/" + id + "/attachments")
                        .file(new MockMultipartFile("file", "err.png", "image/png", PNG))
                        .header("Authorization", bearer(c.renter())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.evidence[0].contentType").value("image/png"));
        // The client's declared type is ignored: an MP4 sent as "image/png" is stored as video.
        mvc.perform(multipart("/api/v1/disputes/" + id + "/attachments")
                        .file(new MockMultipartFile("file", "clip.png", "image/png", MP4))
                        .header("Authorization", bearer(c.s().ownerId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.evidence[1].contentType").value("video/mp4"));

        DisputeView d = disputes.getForCaller(c.renter(), id);
        assertThat(d.evidence()).hasSize(2);
        String videoId = (String) d.evidence().get(1).get("attachmentId");
        String url = "/api/v1/disputes/" + id + "/attachments/" + videoId;

        for (UUID viewer : List.of(c.renter(), c.s().ownerId(), user("SUPPORT"))) {
            mvc.perform(get(url).header("Authorization", bearer(viewer)))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Type", "video/mp4"))
                    .andExpect(content().bytes(MP4));
        }
        mvc.perform(get(url).header("Authorization", bearer(user()))).andExpect(status().isNotFound());
        mvc.perform(get(url)).andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsBadFilesStrangersTooManyAndClosedDisputes() throws Exception {
        Case c = openRenterDispute();
        UUID id = c.dispute().id();

        assertThat(upload(id, c.renter(), "x.svg", "<svg onload=alert(1)>".getBytes())).contains("UNSUPPORTED_MEDIA_TYPE");
        assertThat(upload(id, c.renter(), "x.heic", HEIC)).contains("UNSUPPORTED_MEDIA_TYPE");
        byte[] bigPng = new byte[EvidenceMedia.MAX_IMAGE_BYTES + 1];
        System.arraycopy(PNG, 0, bigPng, 0, PNG.length);
        assertThat(upload(id, c.renter(), "big.png", bigPng)).contains("FILE_TOO_LARGE");
        assertThat(upload(id, user(), "x.png", PNG)).contains("NOT_FOUND");

        for (int i = 0; i < 10; i++) {
            assertThat(upload(id, c.renter(), "x.png", PNG)).contains("attachmentId");
        }
        assertThat(upload(id, c.renter(), "x.png", PNG)).contains("TOO_MANY_ATTACHMENTS");

        disputes.resolve(user("SUPPORT"), id, new Decision(false, 0, 0, "INSUFFICIENT_EVIDENCE", null, null));
        assertThat(upload(id, c.renter(), "x.png", PNG)).contains("INVALID_STATE");
    }

    @Test
    void detectsMediaTypes() {
        assertThat(EvidenceMedia.detect(PNG)).isEqualTo("image/png");
        assertThat(EvidenceMedia.detect(MP4)).isEqualTo("video/mp4");
        assertThat(EvidenceMedia.detect(new byte[] {0, 0, 0, 0x14, 'f', 't', 'y', 'p', 'q', 't', ' ', ' '}))
                .isEqualTo("video/quicktime");
        assertThat(EvidenceMedia.detect(new byte[] {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3})).isEqualTo("video/webm");
        assertThat(EvidenceMedia.detect(HEIC)).isNull();
    }
}
