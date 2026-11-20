package com.rentgg.shared.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

/**
 * Where uploaded media bytes live. Without an {@link ObjectStorage} bean (dev, tests) the bytes stay in the
 * database row; with one (prod: Cloudflare R2) the row only keeps the object key.
 * Rows written before object storage was enabled keep their bytes until the background move picks them up.
 */
@Component
public class MediaStore {

    private static final Logger log = LoggerFactory.getLogger(MediaStore.class);

    private final ObjectStorage storage;

    public MediaStore(ObjectProvider<ObjectStorage> storage) {
        this.storage = storage.getIfAvailable();
    }

    /** True when new media goes to object storage. */
    public boolean external() {
        return storage != null;
    }

    /** What an entity keeps: exactly one of {@code storageKey} or {@code data}. */
    public record Stored(String storageKey, byte[] data, int sizeBytes) {}

    /**
     * Saves the bytes. Object storage is written before the database commits; if the transaction then rolls back,
     * the object is left behind unreferenced (harmless, only a little storage).
     */
    public Stored save(String folder, String contentType, byte[] data) {
        if (storage == null) {
            return new Stored(null, data, data.length);
        }
        String key = folder + "/" + UUID.randomUUID();
        storage.put(key, contentType, data);
        return new Stored(key, null, data.length);
    }

    /** Bytes of a row: inline bytes if it still has them, else the object. */
    public byte[] load(String storageKey, byte[] data) {
        if (data != null) {
            return data;
        }
        if (storage == null) {
            throw new IllegalStateException("Media " + storageKey + " is in object storage but rentgg.media.storage is not s3");
        }
        return storage.get(storageKey);
    }

    /** Deletes the object once the current transaction commits (the row delete is final by then). */
    public void deleteAfterCommit(String storageKey) {
        if (storageKey == null || storage == null) {
            return;
        }
        Runnable delete = () -> {
            try {
                storage.delete(storageKey);
            } catch (RuntimeException e) {
                log.warn("Could not delete media object {}: {}", storageKey, e.toString());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    delete.run();
                }
            });
        } else {
            delete.run();
        }
    }

    /** Direct access for the background move of old rows. */
    public ObjectStorage storage() {
        return storage;
    }
}
