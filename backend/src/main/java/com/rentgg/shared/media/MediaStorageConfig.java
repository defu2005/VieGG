package com.rentgg.shared.media;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** {@code rentgg.media.storage=s3} turns on object storage; anything else keeps media in the database. */
@Configuration
public class MediaStorageConfig {

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "rentgg.media.storage", havingValue = "s3")
    public S3ObjectStorage s3ObjectStorage(@Value("${rentgg.media.s3.endpoint:}") String endpoint,
                                           @Value("${rentgg.media.s3.region:auto}") String region,
                                           @Value("${rentgg.media.s3.bucket:}") String bucket,
                                           @Value("${rentgg.media.s3.access-key:}") String accessKey,
                                           @Value("${rentgg.media.s3.secret-key:}") String secretKey) {
        return new S3ObjectStorage(endpoint, region, bucket, accessKey, secretKey);
    }
}
