package ar.facturas.upload.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings under {@code facturas.storage} in application.yml.
 *
 * <p>A record, so the configuration is immutable once bound and nothing
 * can quietly repoint storage at runtime.
 *
 * @param endpoint     S3 endpoint. S3Mock in dev; empty to use AWS's own.
 * @param bucket       bucket the comprobantes go into
 * @param accessKey    credentials. Dummy values locally
 * @param secretKey    credentials
 * @param region       required by the SDK even when the backend ignores it
 * @param pathStyleAccess {@code true} for S3Mock and MinIO, which expect
 *        {@code host/bucket/key}. Real S3 uses {@code bucket.host/key}.
 */
@ConfigurationProperties(prefix = "facturas.storage")
public record StorageProperties(
        String endpoint,
        String bucket,
        String accessKey,
        String secretKey,
        String region,
        boolean pathStyleAccess) {
}
