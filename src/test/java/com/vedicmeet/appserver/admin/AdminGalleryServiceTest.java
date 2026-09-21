package com.vedicmeet.appserver.admin;

import com.vedicmeet.appserver.cache.CacheService;
import com.vedicmeet.appserver.config.AppConstants;
import com.vedicmeet.appserver.media.MediaUploadService;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class AdminGalleryServiceTest {

    @Test
    void listRejectsUnknownGalleryTypeBeforeMongo() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MediaUploadService uploads = mock(MediaUploadService.class);
        AdminGalleryService service = service(mongo, uploads);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.listGallery(Map.of("galleryType", "avatar")));

        assertEquals("Gallery type should be either live or profile", error.getMessage());
        verifyNoInteractions(mongo, uploads);
    }

    @Test
    void detailsRequiresConsultantIdBeforeMongo() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MediaUploadService uploads = mock(MediaUploadService.class);
        AdminGalleryService service = service(mongo, uploads);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.getConsultantGallery(Map.of("galleryType", "profile")));

        assertEquals("\"consultantId\" is required", error.getMessage());
        verifyNoInteractions(mongo, uploads);
    }

    @Test
    void detailsRejectsInvalidTypeBeforeMongo() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MediaUploadService uploads = mock(MediaUploadService.class);
        AdminGalleryService service = service(mongo, uploads);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.getConsultantGallery(Map.of("consultantId", "507f1f77bcf86cd799439011", "galleryType", "bad")));

        assertEquals("Gallery type should be either live or profile", error.getMessage());
        verifyNoInteractions(mongo, uploads);
    }

    @Test
    void approveRequiresImagesIdBeforeMongo() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MediaUploadService uploads = mock(MediaUploadService.class);
        AdminGalleryService service = service(mongo, uploads);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.approveGalleryImages(Map.of("consultantId", "507f1f77bcf86cd799439011", "galleryType", "profile")));

        assertEquals("\"imagesId\" is required", error.getMessage());
        verifyNoInteractions(mongo, uploads);
    }

    @Test
    void deleteRequiresIdBeforeMongo() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MediaUploadService uploads = mock(MediaUploadService.class);
        AdminGalleryService service = service(mongo, uploads);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.deleteImage(Map.of()));

        assertEquals("\"id\" is required", error.getMessage());
        verifyNoInteractions(mongo, uploads);
    }

    @Test
    void addRequiresImageBeforeMongoOrUpload() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MediaUploadService uploads = mock(MediaUploadService.class);
        AdminGalleryService service = service(mongo, uploads);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.addGallery(Map.of("consultantId", "507f1f77bcf86cd799439011", "galleryType", "profile"), null));

        assertEquals("Image is require", error.getMessage());
        verifyNoInteractions(mongo, uploads);
    }

    @Test
    void addRejectsInvalidTypeBeforeMongoOrUpload() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MediaUploadService uploads = mock(MediaUploadService.class);
        AdminGalleryService service = service(mongo, uploads);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.addGallery(Map.of("consultantId", "507f1f77bcf86cd799439011", "galleryType", "story"), List.of(mock(MultipartFile.class))));

        assertEquals("Gallery type should be either live or profile", error.getMessage());
        verifyNoInteractions(mongo, uploads);
    }

    @Test
    void pipelineJsonStagesParse() {
        AdminGalleryService service = service(mock(MongoTemplate.class), mock(MediaUploadService.class));

        assertDoesNotThrow(() -> service.listGalleryPipeline("ram", "profile", 0, 10)
                .forEach(Document::toJson));
        assertDoesNotThrow(() -> service.consultantGalleryPipeline("507f1f77bcf86cd799439011", "live")
                .forEach(Document::toJson));
    }

    private AdminGalleryService service(MongoTemplate mongo, MediaUploadService uploads) {
        return new AdminGalleryService(mongo, mock(AdminMongoSupport.class), uploads,
                mock(CacheService.class), new AppConstants("bucket", "ap-south-1"));
    }
}
