package com.rentgg;

import com.rentgg.jobs.media.MediaBackfill;
import com.rentgg.shared.media.ObjectStorage;
import com.rentgg.support.IntegrationTest;
import com.rentgg.dispute.application.DisputeService;
import com.rentgg.dispute.application.DisputeView;
import com.rentgg.dispute.domain.DisputeType;
import com.rentgg.helpdesk.application.HelpdeskService;
import com.rentgg.helpdesk.domain.TicketCategory;
import com.rentgg.order.application.HandoverService;
import com.rentgg.order.application.OrderView;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
        /** Fault injection: the n-th put from now fails (0 = off). */
        int failPutNumber;
        int puts;

        void reset() {
            objects.clear();
            failPutNumber = 0;
            puts = 0;
        }

        @Override
        public void put(String key, String contentType, byte[] data) {
            puts++;
            if (failPutNumber > 0 && puts == failPutNumber) {
                throw new IllegalStateException("injected put failure");
            }
            objects.put(key, data.clone());
        }

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
    @Autowired DisputeService disputes;
    @Autowired HandoverService credentials;
    @Autowired HelpdeskService helpdesk;
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

    private long pendingRows() {
        return jdbc.queryForObject("select count(*) from listing_images where storage_key is null", Long.class);
    }

    private static final java.util.concurrent.atomic.AtomicInteger POSITION = new java.util.concurrent.atomic.AtomicInteger(1000);

    /**
     * Inserts a listing image directly with SQL, as a row written before object storage was on: bytes in the DB, no
     * object. It does not go through the upload endpoint, which would store the bytes in the bucket at once.
     */
    private UUID preStorageRow(UUID listingId, byte[] bytes) throws Exception {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into listing_images (id, listing_id, position, content_type, size_bytes, data) "
                + "values (?, ?, ?, 'image/png', ?, ?)", id, listingId, POSITION.incrementAndGet(), bytes.length, bytes);
        return id;
    }

    /** The dispute and support attachment tables that backfill also moves, each with one pre-storage row. */
    @Test
    void backfillMovesDisputeAndSupportAttachmentsUnderTheirOwnFolders() {
        storage.reset();
        backfill.run();
        Setup s = standardListing();
        UUID renter = user();
        OrderView o = paidOrder(renter, s, Duration.ofHours(1), 180);
        advanceAndRun(Duration.ofHours(1));
        credentials.viewCredentials(renter, o.id());
        clock.advance(Duration.ofMinutes(5));
        DisputeView dispute = disputes.open(renter, o.id(), DisputeType.RENTER_LOGIN_FAILED, "wrong password", List.of());
        var ticket = helpdesk.open(renter, "Hỏi phí", TicketCategory.QUESTION, null, "Phí bao nhiêu?");

        UUID disputeAtt = UUID.randomUUID();
        UUID supportAtt = UUID.randomUUID();
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3};
        jdbc.update("insert into dispute_attachments (id, dispute_id, uploaded_by, content_type, size_bytes, data) "
                + "values (?, ?, ?, 'image/jpeg', ?, ?)", disputeAtt, dispute.id(), renter, jpeg.length, jpeg);
        jdbc.update("insert into support_attachments (id, ticket_id, uploaded_by, content_type, size_bytes, data) "
                + "values (?, ?, ?, 'image/jpeg', ?, ?)", supportAtt, ticket.id(), renter, jpeg.length, jpeg);

        // Counts every table backfill moves: at least the two rows inserted here.
        assertThat(backfill.run()).isGreaterThanOrEqualTo(2);
        assertThat(jdbc.queryForObject("select storage_key from dispute_attachments where id = ?", String.class, disputeAtt))
                .isEqualTo("dispute-attachments/" + dispute.id() + "/" + disputeAtt);
        assertThat(jdbc.queryForObject("select data is null from dispute_attachments where id = ?", Boolean.class, disputeAtt))
                .isTrue();
        assertThat(storage.objects.get("dispute-attachments/" + dispute.id() + "/" + disputeAtt)).containsExactly(jpeg);

        assertThat(jdbc.queryForObject("select storage_key from support_attachments where id = ?", String.class, supportAtt))
                .isEqualTo("support-attachments/" + ticket.id() + "/" + supportAtt);
        assertThat(jdbc.queryForObject("select data is null from support_attachments where id = ?", Boolean.class, supportAtt))
                .isTrue();
        assertThat(storage.objects.get("support-attachments/" + ticket.id() + "/" + supportAtt)).containsExactly(jpeg);
    }

    static String sha256(byte[] data) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(data));
    }

    @Test
    void backfillCountsEveryRowAndTheBytesReadBackThroughThePublicPathMatch() throws Exception {
        storage.reset();
        Setup s = standardListing();
        Map<UUID, String> hashes = new java.util.HashMap<>();
        for (int i = 0; i < 5; i++) {
            byte[] bytes = PNG.clone();
            bytes[PNG.length - 1] = (byte) i;
            hashes.put(preStorageRow(s.listingId(), bytes), sha256(bytes));
        }
        long rowsBefore = jdbc.queryForObject("select count(*) from listing_images where listing_id = ?", Long.class, s.listingId());

        long pending = pendingRows();
        assertThat(pending).isGreaterThanOrEqualTo(5);
        assertThat(backfill.run()).isEqualTo(pending);
        assertThat(backfill.run()).isZero();

        assertThat(jdbc.queryForObject("select count(*) from listing_images where listing_id = ?", Long.class, s.listingId()))
                .isEqualTo(rowsBefore);
        for (var e : hashes.entrySet()) {
            byte[] served = mvc.perform(get("/api/v1/listings/" + s.listingId() + "/images/" + e.getKey())
                            .header("Authorization", bearer(s.ownerId())))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
            assertThat(sha256(served)).isEqualTo(e.getValue());
        }
    }

    /** A failing put leaves that row with its bytes and readable; the next run finishes the job. */
    @Test
    void failedPutKeepsTheRowReadableAndTheNextRunFinishes() throws Exception {
        storage.reset();
        backfill.run(); // clear rows left by earlier tests
        Setup s = standardListing();
        UUID a = preStorageRow(s.listingId(), PNG);
        UUID b = preStorageRow(s.listingId(), PNG);
        storage.failPutNumber = 2;

        // One row moves; the second put throws and the run stops with that row still holding its bytes.
        assertThatThrownBy(() -> backfill.run()).isInstanceOf(IllegalStateException.class);
        storage.failPutNumber = 0;

        UUID failed = jdbc.queryForObject("select id from listing_images where listing_id = ? and storage_key is null",
                UUID.class, s.listingId());
        UUID moved = failed.equals(a) ? b : a;
        assertThat(jdbc.queryForObject("select storage_key is null and data is not null from listing_images where id = ?",
                Boolean.class, failed)).isTrue();
        assertThat(jdbc.queryForObject("select storage_key is not null from listing_images where id = ?",
                Boolean.class, moved)).isTrue();
        mvc.perform(get("/api/v1/listings/" + s.listingId() + "/images/" + failed).header("Authorization", bearer(s.ownerId())))
                .andExpect(status().isOk()).andExpect(content().bytes(PNG));

        assertThat(backfill.run()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from listing_images where storage_key is null and listing_id = ?",
                Long.class, s.listingId())).isZero();
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
