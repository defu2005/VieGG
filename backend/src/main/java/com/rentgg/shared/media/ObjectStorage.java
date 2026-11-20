package com.rentgg.shared.media;

/** A bucket of immutable objects (Cloudflare R2, S3, or an in-memory fake in tests). */
public interface ObjectStorage {

    void put(String key, String contentType, byte[] data);

    /** @throws java.util.NoSuchElementException when the object does not exist */
    byte[] get(String key);

    /** No error when the object is already gone. */
    void delete(String key);
}
