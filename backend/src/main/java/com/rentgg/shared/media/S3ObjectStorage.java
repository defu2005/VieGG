package com.rentgg.shared.media;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.net.URI;
import java.util.NoSuchElementException;

/** S3-compatible storage. Tested against Cloudflare R2 (endpoint https://ACCOUNT_ID.r2.cloudflarestorage.com). */
public class S3ObjectStorage implements ObjectStorage, AutoCloseable {

    private final S3Client s3;
    private final String bucket;

    public S3ObjectStorage(String endpoint, String region, String bucket, String accessKey, String secretKey) {
        if (isBlank(endpoint) || isBlank(bucket) || isBlank(accessKey) || isBlank(secretKey)) {
            throw new IllegalStateException("rentgg.media.storage=s3 needs RENTGG_S3_ENDPOINT, RENTGG_S3_BUCKET, "
                    + "RENTGG_S3_ACCESS_KEY and RENTGG_S3_SECRET_KEY");
        }
        this.bucket = bucket;
        this.s3 = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(isBlank(region) ? "auto" : region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .httpClient(UrlConnectionHttpClient.create())
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                // R2 and other S3-compatible stores do not support every checksum the SDK sends by default.
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .build();
    }

    @Override
    public void put(String key, String contentType, byte[] data) {
        s3.putObject(b -> b.bucket(bucket).key(key).contentType(contentType), RequestBody.fromBytes(data));
    }

    @Override
    public byte[] get(String key) {
        try {
            return s3.getObjectAsBytes(b -> b.bucket(bucket).key(key)).asByteArray();
        } catch (NoSuchKeyException e) {
            throw new NoSuchElementException(key);
        }
    }

    @Override
    public void delete(String key) {
        s3.deleteObject(b -> b.bucket(bucket).key(key));
    }

    @Override
    public void close() {
        s3.close();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
