package com.rentgg.shared.media;

import org.gaul.s3proxy.AuthenticationType;
import org.gaul.s3proxy.S3Proxy;
import org.jclouds.ContextBuilder;
import org.jclouds.blobstore.BlobStore;
import org.jclouds.blobstore.BlobStoreContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.util.NoSuchElementException;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S3ObjectStorage against an in-JVM S3 server (s3proxy) that verifies AWS signatures: a wrong secret is refused.
 * No Spring context. Cloudflare R2 itself stays [S].
 */
class S3ObjectStorageTest {

    private static final String ACCESS = "test-access";
    private static final String SECRET = "test-secret";
    private static final String BUCKET = "media";

    private static BlobStoreContext context;
    private static S3Proxy proxy;
    private static String endpoint;

    @BeforeAll
    static void start() throws Exception {
        int port;
        try (ServerSocket ss = new ServerSocket(0)) {
            port = ss.getLocalPort();
        }
        endpoint = "http://127.0.0.1:" + port;
        context = ContextBuilder.newBuilder("transient").credentials(ACCESS, SECRET).build(BlobStoreContext.class);
        BlobStore blobs = context.getBlobStore();
        blobs.createContainerInLocation(null, BUCKET);
        proxy = S3Proxy.builder().blobStore(blobs).endpoint(URI.create(endpoint))
                .awsAuthentication(AuthenticationType.AWS_V2_OR_V4, ACCESS, SECRET).build();
        proxy.start();
    }

    @AfterAll
    static void stop() throws Exception {
        proxy.stop();
        context.close();
    }

    private static S3ObjectStorage storage(String secret, String bucket) {
        return new S3ObjectStorage(endpoint, "auto", bucket, ACCESS, secret);
    }

    /** 5 MiB + 7 bytes: above the 5 MiB multipart boundary, with every byte value in it. */
    private static byte[] bigPayload() {
        byte[] b = new byte[5 * 1024 * 1024 + 7];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) (i & 0xFF);
        }
        return b;
    }

    @Test
    void roundTripOfEveryByteValueAboveTheMultipartBoundary() {
        S3ObjectStorage s = storage(SECRET, BUCKET);
        byte[] data = bigPayload();
        s.put("round/big.bin", "application/octet-stream", data);
        assertThat(s.get("round/big.bin")).isEqualTo(data);
    }

    @Test
    void overwriteReplacesTheObjectAndDeleteThenReadFails() {
        S3ObjectStorage s = storage(SECRET, BUCKET);
        s.put("over/x", "text/plain", new byte[]{1, 2, 3});
        s.put("over/x", "text/plain", new byte[]{4, 5});
        assertThat(s.get("over/x")).containsExactly(4, 5);

        s.delete("over/x");
        assertThatThrownBy(() -> s.get("over/x")).isInstanceOf(NoSuchElementException.class);
        s.delete("over/x"); // deleting a missing object is not an error
    }

    /** The key format MediaStore generates for uploads: folder/parent/uuid. */
    @Test
    void keysFromMediaStoreRoundTripUnderTheirFolder() {
        S3ObjectStorage s = storage(SECRET, BUCKET);
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("storage", s);
        ObjectProvider<ObjectStorage> provider = beans.getBeanProvider(ObjectStorage.class);
        MediaStore media = new MediaStore(provider);

        String parent = java.util.UUID.randomUUID().toString();
        MediaStore.Stored stored = media.save("listing-images/" + parent, "image/png", new byte[]{7, 8});

        assertThat(stored.storageKey()).matches(Pattern.compile(
                "listing-images/" + parent + "/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
        assertThat(media.load(stored.storageKey(), null)).containsExactly(7, 8);
    }

    @Test
    void wrongSecretIsRefusedAsForbidden() {
        S3ObjectStorage wrong = storage("not-the-secret", BUCKET);
        assertThatThrownBy(() -> wrong.put("x/wrong", "text/plain", new byte[]{1}))
                .isInstanceOf(S3Exception.class)
                .satisfies(e -> assertThat(((S3Exception) e).statusCode()).isEqualTo(403));
    }

    @Test
    void missingBucketFailsClearly() {
        S3ObjectStorage missing = storage(SECRET, "no-such-bucket");
        assertThatThrownBy(() -> missing.put("x/y", "text/plain", new byte[]{1}))
                .isInstanceOf(NoSuchBucketException.class);
    }
}
