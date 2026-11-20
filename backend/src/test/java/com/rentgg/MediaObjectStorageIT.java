package com.rentgg;

import com.rentgg.jobs.media.MediaBackfill;
import com.rentgg.shared.media.ObjectStorage;
import com.rentgg.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Media in object storage (R2 on prod): new uploads, reads, deletes, and moving rows that still hold bytes. */
@TestPropertySource(properties = "rentgg.media.backfill=false")
class MediaObjectStorageIT extends IntegrationTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D};

    static class InMemoryStorage implements ObjectStorage {
        final Map<String, byte[]> objects = new ConcurrentHashMap<>();

        @Override
        public void put(String key, String contentType, byte[] data) { objects.put(key, data.clone()); }

        @Override
        public byte[] get(String key) {
            byte[] d = objects.get(key);
            if (d == null) {
                throw new NoSuchElementException(key);
            }
            return d;
        }

        @Override
        public void delete(String key) { objects.remove(key); }
    }

    @TestConfiguration
    static class StorageConfig {
        @Bean
        InMemoryStorage inMemoryStorage() {
            return new InMemoryStorage();
        }
    }

    @Autowired InMemoryStorage storage;
    @Autowired MediaBackfill backfill;

    @Test
    void newUploadGoesToObjectStorageAndIsServedAndDeleted() throws Exception {
        Setup s = standardListing();
        mvc.perform(multipart("/api/v1/listings/" + s.listingId() + "/images")
                        .file(new MockMultipartFile("file", "a.png", "image/png", PNG))
                        .header("Authorization", bearer(s.ownerId())))
                .andExpect(status().isCreated());
        UUID imageId = listings.get(s.listingId()).imageIds().get(0);

        Map<String, Object> row = jdbc.queryForMap("select storage_key, data from listing_images where id = ?", imageId);
        assertThat(row.get("data")).isNull();
        String key = (String) row.get("storage_key");
        assertThat(key).startsWith("listing-images/" + s.listingId() + "/");
        assertThat(storage.objects).containsKey(key);

        String url = "/api/v1/listings/" + s.listingId() + "/images/" + imageId;
        mvc.perform(get(url).header("Authorization", bearer(s.ownerId())))
                .andExpect(status().isOk())
                .andExpect(content().bytes(PNG));

        mvc.perform(delete(url).header("Authorization", bearer(s.ownerId()))).andExpect(status().isOk());
        assertThat(storage.objects).doesNotContainKey(key);
    }

    @Test
    void backfillMovesBytesStillInTheDatabase() throws Exception {
        Setup s = standardListing();
        UUID imageId = UUID.randomUUID();
        // A row written before object storage was turned on.
        jdbc.update("insert into listing_images (id, listing_id, position, content_type, size_bytes, data) "
                + "values (?, ?, 1, 'image/png', ?, ?)", imageId, s.listingId(), PNG.length, PNG);

        assertThat(backfill.run()).isGreaterThanOrEqualTo(1);
        assertThat(backfill.run()).isZero();

        Map<String, Object> row = jdbc.queryForMap("select storage_key, data from listing_images where id = ?", imageId);
        assertThat(row.get("data")).isNull();
        assertThat(row.get("storage_key")).isEqualTo("listing-images/" + s.listingId() + "/" + imageId);

        mvc.perform(get("/api/v1/listings/" + s.listingId() + "/images/" + imageId)
                        .header("Authorization", bearer(s.ownerId())))
                .andExpect(status().isOk())
                .andExpect(content().bytes(PNG));
    }
}
