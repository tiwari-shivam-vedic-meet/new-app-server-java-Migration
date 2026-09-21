package com.vedicmeet.appserver.media;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import static org.junit.jupiter.api.Assertions.*;

/** Pins the faithful S3 key-generation of {@link MediaUploadService} (Node awsUpload key format). */
class MediaUploadServiceTest {

    private final MediaUploadService svc =
            new MediaUploadService("", "", "ap-south-1", "bucket", "Vedic_Meet");

    @Test
    void blankStaticKeys_useAwsDefaultCredentialChain() {
        assertInstanceOf(DefaultCredentialsProvider.class, svc.credentialsProvider());
    }

    @Test
    void completeStaticKeys_keepLegacyEnvironmentCompatibility() {
        MediaUploadService configured = new MediaUploadService(
                "test-access", "test-secret", "ap-south-1", "bucket", "Vedic_Meet");
        assertInstanceOf(StaticCredentialsProvider.class, configured.credentialsProvider());
    }

    @Test
    void buildKey_usesProjectTypeUuidAndExtension() {
        String key = svc.buildKey("support/chat", "photo.JPG");
        assertTrue(key.startsWith("Vedic_Meet/support/chat/"), key);
        assertTrue(key.endsWith(".JPG"), "extension preserved: " + key);
        String uuidPart = key.substring("Vedic_Meet/support/chat/".length(), key.length() - ".JPG".length());
        assertEquals(36, uuidPart.length(), "uuidv4 length");
    }

    @Test
    void extname_matchesNodePathExtname() {
        assertEquals(".jpg", svc.extname("a.jpg"));
        assertEquals(".c", svc.extname("a.b.c"));
        assertEquals("", svc.extname("noext"));
        assertEquals("", svc.extname(".bashrc"), "leading-dot file has no extension");
        assertEquals(".png", svc.extname("dir/sub/img.png"));
        assertEquals("", svc.extname(null));
    }

    @Test
    void differentUuidsEachCall() {
        assertNotEquals(svc.buildKey("user", "x.png"), svc.buildKey("user", "x.png"));
    }

    @Test
    void uploadMultipart_reallyInvokesS3PutObject_andReturnsTheStoredKey() {
        S3Client s3 = mock(S3Client.class);
        MediaUploadService service = new MediaUploadService("ap-south-1", "test-bucket", "Vedic_Meet", s3);
        MockMultipartFile file = new MockMultipartFile(
                "profileImage", "avatar.png", "image/png", "image".getBytes(StandardCharsets.UTF_8));

        String key = service.upload(file, "user");

        assertTrue(key.startsWith("Vedic_Meet/user/"));
        assertTrue(key.endsWith(".png"));
        verify(s3).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void uploadBuffer_reallyInvokesS3PutObject_andReturnsTheStoredKey() {
        S3Client s3 = mock(S3Client.class);
        MediaUploadService service = new MediaUploadService("ap-south-1", "test-bucket", "Vedic_Meet", s3);

        String key = service.upload("pdf".getBytes(StandardCharsets.UTF_8), "invoice", ".pdf", "application/pdf");

        assertTrue(key.startsWith("Vedic_Meet/invoice/"));
        assertTrue(key.endsWith(".pdf"));
        verify(s3).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }
}
