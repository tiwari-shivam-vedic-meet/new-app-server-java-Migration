package com.vedicmeet.appserver.updates;

import com.vedicmeet.appserver.cache.CacheService;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

/**
 * Port of the manifest read path in Node modules/app-updates.js (+ BundleStorage).
 *
 * GET /updates/manifest.json:
 *   - cacheKey app:manifest:{platform}, 1h TTL
 *   - read update-bundles/manifest.json (local FS by default; S3 optional)
 *   - applyCdnUrlsToManifest: if a CDN base is configured, rewrite the two bundle URLs
 *   - return the manifest object as-is (NOT wrapped in the ApiResponse envelope)
 *   - missing manifest -> null here -> HTTP 404 {success:false} in the controller
 *
 * Default backend is local disk (Node: process.cwd()/update-bundles). The optional S3
 * backend (BUNDLE_USE_S3=true, plus explicit AWS access/secret keys) reads the manifest
 * from `${BUNDLE_S3_PREFIX}/manifest.json` via the AWS SDK v2 and, mirroring Node
 * bundle-storage.js, falls back to local disk on any S3 error rather than throwing.
 */
@Service
public class UpdatesService {

    private final CacheService cache;
    private final String bundlesDir;
    private final String cdnBaseUrl;   // BUNDLE_CDN_BASE_URL (null/empty = none)
    private final boolean useS3;       // BUNDLE_USE_S3
    private final String s3Prefix;     // BUNDLE_S3_PREFIX (Node default 'Vedic_Meet/ota-bundles')
    private final String bucket;       // AWS_S3_BUCKET
    private final String region;       // AWS_REGION
    private final String accessKey;    // S3 path requires explicit creds, mirroring Node useS3()
    private final String secretKey;

    private volatile S3Client s3;

    public UpdatesService(CacheService cache,
                          @Value("${vedicmeet.bundles.dir:update-bundles}") String bundlesDir,
                          @Value("${BUNDLE_CDN_BASE_URL:}") String cdnBaseUrl,
                          @Value("${BUNDLE_USE_S3:false}") boolean useS3,
                          @Value("${vedicmeet.bundles.s3-prefix:Vedic_Meet/ota-bundles}") String s3Prefix,
                          @Value("${vedicmeet.aws.bucket:}") String bucket,
                          @Value("${vedicmeet.aws.region:}") String region,
                          @Value("${vedicmeet.aws.access-key:}") String accessKey,
                          @Value("${vedicmeet.aws.secret-key:}") String secretKey) {
        this.cache = cache;
        this.bundlesDir = bundlesDir;
        this.cdnBaseUrl = cdnBaseUrl;
        this.useS3 = useS3;
        this.s3Prefix = s3Prefix;
        this.bucket = bucket;
        this.region = region;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
    }

    /** Returns the manifest document (CDN-rewritten if configured), or null if absent. */
    public Document getManifest(String platformParam) {
        String platform = (platformParam == null || platformParam.isEmpty()) ? "all" : platformParam;
        String cacheKey = "app:manifest:" + platform;

        Document cached = cache.get(cacheKey, Document.class);
        if (cached != null) {
            return cached;
        }

        String manifestRaw = readManifestText();
        if (manifestRaw == null) {
            return null; // controller maps this to HTTP 404
        }

        Document manifest = Document.parse(manifestRaw);
        applyCdnUrls(manifest);

        cache.set(cacheKey, manifest, 60 * 60); // 1 hour, as in Node
        return manifest;
    }

    private String readManifestText() {
        // Node bundle-storage.js readText('manifest.json'): try S3 when enabled, but ALWAYS fall back
        // to local on any failure (never throws). Node useS3() also requires explicit access/secret keys.
        if (s3Enabled()) {
            String fromS3 = readManifestFromS3();
            if (fromS3 != null) {
                return fromS3;
            }
        }
        try {
            Path path = Paths.get(bundlesDir, "manifest.json");
            if (!Files.exists(path)) {
                return null;
            }
            return Files.readString(path);
        } catch (Exception e) {
            return null;
        }
    }

    /** Mirrors Node useS3(): BUNDLE_USE_S3 AND explicit access+secret keys (plus bucket/region) present. */
    private boolean s3Enabled() {
        return useS3
                && accessKey != null && !accessKey.isBlank()
                && secretKey != null && !secretKey.isBlank()
                && bucket != null && !bucket.isBlank()
                && region != null && !region.isBlank();
    }

    /** Mirrors Node s3Key(filename): `${S3_PREFIX}/${filename}` with duplicate slashes collapsed. */
    private String s3Key(String filename) {
        return (s3Prefix + "/" + filename).replaceAll("/+", "/");
    }

    /** Reads manifest.json from S3; returns null on any error so the caller falls back to local (Node quirk). */
    private String readManifestFromS3() {
        try {
            byte[] body = s3().getObjectAsBytes(
                    GetObjectRequest.builder().bucket(bucket).key(s3Key("manifest.json")).build())
                    .asByteArray();
            return new String(body, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /** Lazily built S3 client (only when S3 is enabled with explicit creds); app boots without AWS. */
    private S3Client s3() {
        S3Client local = s3;
        if (local == null) {
            synchronized (this) {
                local = s3;
                if (local == null) {
                    local = S3Client.builder()
                            .region(Region.of(region))
                            .credentialsProvider(StaticCredentialsProvider.create(
                                    AwsBasicCredentials.create(accessKey, secretKey)))
                            .build();
                    s3 = local;
                }
            }
        }
        return local;
    }

    /** Mirrors applyCdnUrlsToManifest: only rewrites when a CDN base is configured. */
    private void applyCdnUrls(Document manifest) {
        if (cdnBaseUrl == null || cdnBaseUrl.isEmpty()) {
            return;
        }
        String base = cdnBaseUrl.replaceAll("/$", "");
        manifest.put("androidBundleUrl", base + "/index.android.bundle");
        manifest.put("iosBundleUrl", base + "/index.ios.bundle");
    }
}
