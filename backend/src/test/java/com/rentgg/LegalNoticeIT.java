package com.rentgg;

import com.rentgg.legal.application.LegalNoticeService;
import com.rentgg.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** UC-20 intake (Dev Guide §3 mục 13): recorded with a response deadline, staff only. */
class LegalNoticeIT extends IntegrationTest {

    @org.springframework.beans.factory.annotation.Autowired com.rentgg.legal.application.LegalNoticeService legal;

    private static final String BODY = "{\"gameId\":null,\"publisherName\":\"Nhà phát hành X\",\"referenceNo\":\"TB-1\","
            + "\"subject\":\"Yêu cầu gỡ dịch vụ thuê\",\"body\":\"Nội dung thông báo pháp lý đầy đủ.\"}";

    @Test
    void staffRecordsNoticeWithDeadlineThirtyDaysAfterReceipt() throws Exception {
        String admin = bearer(user("ADMIN"));
        String created = mvc.perform(post("/api/v1/support/legal-notices").header("Authorization", admin)
                        .contentType("application/json").content(BODY))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(created).contains("\"status\":\"OPEN\"").contains("Nhà phát hành X");

        var list = mvc.perform(get("/api/v1/support/legal-notices").param("status", "OPEN")
                        .header("Authorization", admin))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(list).contains("TB-1");

        UUID id = UUID.fromString(created.replaceAll(".*\"id\":\"([0-9a-f-]{36})\".*", "$1"));
        LegalNoticeService.Notice n = legal.get(id);
        assertThat(n.responseDeadline()).isEqualTo(n.receivedAt().plus(LegalNoticeService.RESPONSE_WINDOW));
        assertThat(LegalNoticeService.RESPONSE_WINDOW).isEqualTo(Duration.ofDays(30));
    }

    @Test
    void rejectsFutureReceiptAndMissingFieldsAndRenters() throws Exception {
        String admin = bearer(user("ADMIN"));
        mvc.perform(post("/api/v1/support/legal-notices").header("Authorization", admin)
                        .contentType("application/json").content("{\"publisherName\":\"\",\"subject\":\"x\",\"body\":\"y\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/support/legal-notices").header("Authorization", bearer(user()))
                        .contentType("application/json").content(BODY))
                .andExpect(status().isForbidden());
    }
}
