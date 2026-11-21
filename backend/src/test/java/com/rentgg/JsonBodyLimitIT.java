package com.rentgg;

import com.rentgg.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** §12.10: a JSON body over 1 MB gets 413 before the controller; smaller bodies pass through unchanged. */
class JsonBodyLimitIT extends IntegrationTest {

    private static byte[] loginWithPadding(int paddingBytes) {
        return ("{\"email\":\"a@b.dev\",\"password\":\"" + "x".repeat(paddingBytes) + "\"}").getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void jsonBodyOverOneMegabyteIsRejectedWith413() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                        .content(loginWithPadding(1_048_577)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("PAYLOAD_TOO_LARGE")));
    }

    @Test
    void jsonBodyUnderTheLimitReachesTheController() throws Exception {
        // Wrong credentials: the controller answers 401, not 413, so the body was read and handled.
        mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                        .content(loginWithPadding(900_000)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void vendorJsonTypesAreLimitedToo() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType("application/vnd.api+json")
                        .content(loginWithPadding(1_048_577)))
                .andExpect(status().isPayloadTooLarge());
    }

    @Test
    void multipartUploadsAreNotLimitedByTheJsonRule() throws Exception {
        var file = new org.springframework.mock.web.MockMultipartFile("file", "a.bin", "application/octet-stream",
                new byte[1_100_000]);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/auth/login").file(file))
                .andExpect(status().is(org.hamcrest.Matchers.not(413)));
    }
}
