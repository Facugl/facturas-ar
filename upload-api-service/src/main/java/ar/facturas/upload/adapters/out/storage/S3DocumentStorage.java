package ar.facturas.upload.adapters.out.storage;

import ar.facturas.domain.port.DocumentStorage;
import ar.facturas.upload.config.StorageProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * Stores comprobantes in any S3-compatible backend.
 *
 * <p>The only class in the project that knows S3 exists. Everything else
 * talks to {@link DocumentStorage}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class S3DocumentStorage implements DocumentStorage {

    /** Keeps the key's extension honest without trusting the filename. */
    private static final Map<String, String> EXTENSIONS = Map.of(
            "application/pdf", "pdf",
            "image/jpeg", "jpg",
            "image/jpg", "jpg",
            "image/png", "png");

    private final S3Client s3;
    private final StorageProperties props;

    /**
     * Creates the bucket if it is missing, and fails startup if storage is
     * unreachable.
     *
     * <p>Doing this in the application rather than in the container setup
     * means it works against any backend — S3Mock, MinIO, real S3 — and
     * does not depend on a particular image honouring a particular
     * environment variable. S3Mock, for one, silently ignores its own
     * {@code initialBuckets}.
     *
     * <p>Failing fast is deliberate: it buys the contract "if the app is
     * up, storage works". The alternative — start anyway and fail on the
     * first upload — trades a clear error at boot for a confusing one in
     * production.
     */
    @PostConstruct
    void ensureBucketExists() {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(props.bucket()).build());
            log.info("Storage ready: bucket '{}' at {}", props.bucket(), props.endpoint());
        } catch (NoSuchBucketException e) {
            // Solo lo lanza el servicio cuando responde; una conexion
            // caida nunca llega hasta aca.
            s3.createBucket(CreateBucketRequest.builder().bucket(props.bucket()).build());
            log.info("Storage ready: bucket '{}' created at {}", props.bucket(), props.endpoint());
        } catch (SdkException e) {
            throw new StorageException(
                    "Cannot reach object storage at " + props.endpoint() + " (bucket '"
                    + props.bucket() + "'). Is the container up? `docker compose up -d s3`", e);
        }
    }

    @Override
    public String store(byte[] content, String contentType) {
        String key = newKey(contentType);
        try {
            s3.putObject(
                    PutObjectRequest.builder()
                            .bucket(props.bucket())
                            .key(key)
                            .contentType(contentType)
                            .contentLength((long) content.length)
                            .build(),
                    RequestBody.fromBytes(content));
            log.debug("Stored {} bytes at {}", content.length, key);
            return key;
        } catch (SdkException e) {
            throw new StorageException("Could not store the file under key " + key, e);
        }
    }

    @Override
    public byte[] retrieve(String storageKey) {
        try {
            return s3.getObjectAsBytes(
                    GetObjectRequest.builder()
                            .bucket(props.bucket())
                            .key(storageKey)
                            .build()
            ).asByteArray();
        } catch (NoSuchKeyException e) {
            throw new StorageException("No file stored under key " + storageKey, e);
        } catch (SdkException e) {
            throw new StorageException("Could not read the file under key " + storageKey, e);
        }
    }

    @Override
    public void delete(String storageKey) {
        try {
            // S3 returns success for a key that was never there, which is
            // the behaviour the port promises.
            s3.deleteObject(DeleteObjectRequest.builder()
                    .bucket(props.bucket())
                    .key(storageKey)
                    .build());
        } catch (SdkException e) {
            throw new StorageException("Could not delete the file under key " + storageKey, e);
        }
    }

    /**
     * {@code documents/2026/10/<uuid>.pdf}
     *
     * <p>Partitioned by year and month so that listing a prefix stays
     * usable once there are a lot of files, and built from a UUID rather
     * than the uploaded filename: that name comes from the user, may
     * collide, and would put arbitrary text into storage paths.
     */
    private String newKey(String contentType) {
        LocalDate today = LocalDate.now();
        String extension = EXTENSIONS.getOrDefault(
                contentType == null ? "" : contentType.toLowerCase(), "bin");
        return "documents/%d/%02d/%s.%s".formatted(
                today.getYear(), today.getMonthValue(), UUID.randomUUID(), extension);
    }
}
