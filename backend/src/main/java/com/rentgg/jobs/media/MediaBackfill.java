package com.rentgg.jobs.media;

import com.rentgg.shared.media.MediaStore;
import com.rentgg.shared.media.ObjectStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Once object storage is on, moves media still kept in database rows (uploaded before) to the bucket, in small
 * batches on a background thread. Safe to run on several instances or restart halfway: a row is only switched to
 * its object key if it still holds its bytes, and an object written twice is simply overwritten.
 * After it finishes, run {@code VACUUM FULL} on the three tables to give the disk space back.
 */
@Component
public class MediaBackfill {

    private static final Logger log = LoggerFactory.getLogger(MediaBackfill.class);
    private static final int BATCH = 20;

    /** Table → key folder, grouped by the owning row like new uploads ({@code folder/<parent id>/<id>}). */
    private record Table(String name, String parentColumn, String folder) {}

    private static final List<Table> TABLES = List.of(
            new Table("listing_images", "listing_id", "listing-images"),
            new Table("dispute_attachments", "dispute_id", "dispute-attachments"),
            new Table("support_attachments", "ticket_id", "support-attachments"));

    private final MediaStore media;
    private final JdbcTemplate jdbc;
    private final boolean enabled;

    public MediaBackfill(MediaStore media, JdbcTemplate jdbc,
                         @Value("${rentgg.media.backfill:true}") boolean enabled) {
        this.media = media;
        this.jdbc = jdbc;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (enabled && media.external()) {
            Thread.ofVirtual().name("media-backfill").start(this::runQuietly);
        }
    }

    private void runQuietly() {
        try {
            int moved = run();
            if (moved > 0) {
                log.info("Media backfill done: {} files moved to object storage", moved);
            }
        } catch (RuntimeException e) {
            log.error("Media backfill stopped; it resumes on next start", e);
        }
    }

    /** Moves every remaining row; returns how many were moved. */
    public int run() {
        ObjectStorage storage = media.storage();
        if (storage == null) {
            return 0;
        }
        int total = 0;
        for (Table t : TABLES) {
            while (true) {
                List<Row> rows = jdbc.query(
                        "select id, " + t.parentColumn() + " as parent, content_type, data from " + t.name()
                                + " where storage_key is null order by id limit " + BATCH,
                        (rs, i) -> new Row(rs.getObject("id", UUID.class), rs.getObject("parent", UUID.class),
                                rs.getString("content_type"), rs.getBytes("data")));
                if (rows.isEmpty()) {
                    break;
                }
                for (Row r : rows) {
                    String key = t.folder() + "/" + r.parent() + "/" + r.id();
                    storage.put(key, r.contentType(), r.data());
                    total += jdbc.update("update " + t.name() + " set storage_key = ?, data = null "
                            + "where id = ? and storage_key is null", key, r.id());
                }
            }
        }
        return total;
    }

    private record Row(UUID id, UUID parent, String contentType, byte[] data) {}
}
