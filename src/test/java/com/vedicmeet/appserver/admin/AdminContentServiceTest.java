package com.vedicmeet.appserver.admin;

import com.vedicmeet.appserver.cache.CacheService;
import com.vedicmeet.appserver.config.AppConstants;
import com.vedicmeet.appserver.media.MediaUploadService;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class AdminContentServiceTest {

    @Test
    void bannerRequiresImageBeforeAnyDatabaseWrite() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        AdminContentService service = service(mongo);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.addBanner("Home", "507f1f77bcf86cd799439011", "user", null));

        assertEquals("IMAGE_REQUIRE", error.getMessage());
        verifyNoInteractions(mongo);
    }

    @Test
    void bannerRejectsUnknownUserTypeBeforeUploadOrDatabaseWrite() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MediaUploadService uploads = mock(MediaUploadService.class);
        AdminContentService service = new AdminContentService(mongo, uploads,
                mock(CacheService.class), new AppConstants("bucket", "ap-south-1"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.addBanner("Home", "507f1f77bcf86cd799439011", "root", mock(org.springframework.web.multipart.MultipartFile.class)));

        assertEquals("INVALID_USER_TYPE", error.getMessage());
        verifyNoInteractions(mongo, uploads);
    }

    private AdminContentService service(MongoTemplate mongo) {
        return new AdminContentService(mongo, mock(MediaUploadService.class),
                mock(CacheService.class), new AppConstants("bucket", "ap-south-1"));
    }
}
