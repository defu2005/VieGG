package com.rentgg;

import com.rentgg.listing.domain.ListingStatus;
import com.rentgg.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Account screenshots on listings: upload rules, review on change, visibility, delete. */
class ListingImageIT extends IntegrationTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D};

    private static MockMultipartFile file(byte[] data) {
        return new MockMultipartFile("file", "shot.png", "image/png", data);
    }

    private String upload(Setup s, UUID as, byte[] data) throws Exception {
        return mvc.perform(multipart("/api/v1/listings/" + s.listingId() + "/images").file(file(data))
                        .header("Authorization", bearer(as)))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void ownerUploadsImageAndActiveListingGoesBackToReview() throws Exception {
        Setup s = standardListing();
        assertThat(listings.get(s.listingId()).status()).isEqualTo(ListingStatus.ACTIVE);

        mvc.perform(multipart("/api/v1/listings/" + s.listingId() + "/images").file(file(PNG))
                        .header("Authorization", bearer(s.ownerId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.imageIds.length()").value(1))
                .andExpect(jsonPath("$.status").value("PENDING_REVIEW"));

        UUID imageId = listings.get(s.listingId()).imageIds().get(0);
        String url = "/api/v1/listings/" + s.listingId() + "/images/" + imageId;

        // Not ACTIVE any more: hidden from the public, visible to the owner.
        mvc.perform(get(url)).andExpect(status().isNotFound());
        mvc.perform(get(url).header("Authorization", bearer(s.ownerId())))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().bytes(PNG));

        UUID admin = user("ADMIN");
        listings.approve(admin, s.listingId());
        mvc.perform(get(url)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/listings/search").param("gameId", s.gameId().toString())).andExpect(jsonPath("$.content[0].imageIds[0]").value(imageId.toString()));

        // Delete keeps the listing ACTIVE.
        mvc.perform(delete(url).header("Authorization", bearer(s.ownerId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imageIds.length()").value(0))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        mvc.perform(get(url)).andExpect(status().isNotFound());
    }

    @Test
    void rejectsBadFilesOtherUsersAndTooManyImages() throws Exception {
        Setup s = standardListing();

        // Content type claimed by the client is ignored; the bytes decide.
        assertThat(upload(s, s.ownerId(), "<svg onload=alert(1)>".getBytes())).contains("UNSUPPORTED_IMAGE_TYPE");
        // MockMvc skips the servlet multipart limit (FILE_TOO_LARGE); the service check still applies.
        assertThat(upload(s, s.ownerId(), new byte[1024 * 1024 + 1])).contains("IMAGE_TOO_LARGE");

        mvc.perform(multipart("/api/v1/listings/" + s.listingId() + "/images").file(file(PNG))
                        .header("Authorization", bearer(user())))
                .andExpect(status().isNotFound());
        mvc.perform(multipart("/api/v1/listings/" + s.listingId() + "/images").file(file(PNG)))
                .andExpect(status().isUnauthorized());

        for (int i = 0; i < 8; i++) {
            assertThat(upload(s, s.ownerId(), PNG)).contains("imageIds");
        }
        assertThat(upload(s, s.ownerId(), PNG)).contains("TOO_MANY_IMAGES");
        assertThat(listings.get(s.listingId()).imageIds()).hasSize(8);
    }
}
