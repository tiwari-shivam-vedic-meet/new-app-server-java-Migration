package com.vedicmeet.appserver.media;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.util.UUID;

/**
 * FAITHFUL port of the Node awsUpload({ file, type }) helper (utils/aws/upload.js L7-46).
 *
 * The KEY-GENERATION logic is ported exactly and unit-tested: an S3 object key
 * {@code <projectName>/<type>/<uuid><ext>} where {@code <ext>} is Node's path.extname(file.name).
 * The PUT itself uses the AWS SDK v2; the client is built lazily from config so the app boots without
 * AWS credentials (they are supplied per-environment, never hardcoded). Node returns the S3 object Key
 * on success - this returns the same Key.
 */
@Service
public class MediaUploadService {

    private final String accessKey;
    private final String secretKey;
    private final String region;
    private final String bucket;
    private final String projectName;

    private volatile S3Client s3;

    @Autowired
    public MediaUploadService(
            @Value("${vedicmeet.aws.access-key:}") String accessKey,
            @Value("${vedicmeet.aws.secret-key:}") String secretKey,
            @Value("${vedicmeet.aws.region:}") String region,
            @Value("${vedicmeet.aws.bucket:}") String bucket,
            @Value("${vedicmeet.aws.project-name:Vedic_Meet}") String projectName) {
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.region = region;
        this.bucket = bucket;
        this.projectName = projectName;
    }

    /** Test-only/integration constructor: proves the PUT contract without contacting AWS. */
    MediaUploadService(String region, String bucket, String projectName, S3Client s3) {
        this.accessKey = "";
        this.secretKey = "";
        this.region = region;
        this.bucket = bucket;
        this.projectName = projectName;
        this.s3 = s3;
    }

    /** Mirrors awsUpload({file, type}) -> stores the object and returns its S3 key. */
    public String upload(MultipartFile file, String type) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Either file or buffer must be provided for upload");
        }
        String key = buildKey(type, file.getOriginalFilename());
        String contentType = file.getContentType() != null ? file.getContentType() : "application/octet-stream";
        try {
            s3().putObject(
                    PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build(),
                    RequestBody.fromBytes(file.getBytes()));
        } catch (IOException e) {
            throw new RuntimeException("Upload body is empty or invalid", e);
        }
        return key;
    }

    /** Buffer variant used by invoice/report producers that do not have a MultipartFile. */
    public String upload(byte[] buffer, String type, String extension, String contentType) {
        if (buffer == null || buffer.length == 0) {
            throw new IllegalArgumentException("Either file or buffer must be provided for upload");
        }
        String normalizedExtension = extension == null || extension.isBlank() ? ""
                : (extension.startsWith(".") ? extension : "." + extension);
        String key = projectName + "/" + type + "/" + UUID.randomUUID() + normalizedExtension;
        s3().putObject(
                PutObjectRequest.builder().bucket(bucket).key(key)
                        .contentType(contentType == null || contentType.isBlank()
                                ? "application/octet-stream" : contentType)
                        .build(),
                RequestBody.fromBytes(buffer));
        return key;
    }

    /** FAITHFUL key format: {@code <projectName>/<type>/<uuid><ext>} (uuid = uuidv4()). */
    public String buildKey(String type, String originalFilename) {
        return projectName + "/" + type + "/" + UUID.randomUUID() + extname(originalFilename);
    }

    /** Node path.extname: last dot of the basename, or "" when none / a leading-dot file. */
    String extname(String name) {
        if (name == null) return "";
        String base = name;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) base = base.substring(slash + 1);
        int dot = base.lastIndexOf('.');
        if (dot <= 0) return "";
        return base.substring(dot);
    }

    private S3Client s3() {
        S3Client local = s3;
        if (local == null) {
            synchronized (this) {
                local = s3;
                if (local == null) {
                    if (region == null || region.isBlank() || bucket == null || bucket.isBlank()) {
                        throw new IllegalStateException("AWS S3 region and bucket must be configured");
                    }
                    S3ClientBuilder builder = S3Client.builder().region(Region.of(region));
                    builder.credentialsProvider(credentialsProvider());
                    local = builder.build();
                    s3 = local;
                }
            }
        }
        return local;
    }

    /** Prefer explicit legacy credentials only when both are present; otherwise use IAM role/profile. */
    AwsCredentialsProvider credentialsProvider() {
        if (accessKey != null && !accessKey.isBlank() && secretKey != null && !secretKey.isBlank()) {
            return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
        }
        return DefaultCredentialsProvider.create();
    }
}
