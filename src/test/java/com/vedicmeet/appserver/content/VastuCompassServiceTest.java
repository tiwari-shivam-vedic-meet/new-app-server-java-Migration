package com.vedicmeet.appserver.content;

import com.vedicmeet.appserver.cache.CacheService;
import com.vedicmeet.appserver.config.AppConstants;
import com.vedicmeet.appserver.discovery.MembershipService;
import com.vedicmeet.appserver.integrations.CleverTapClient;
import com.vedicmeet.appserver.notification.PushNotificationService;
import com.vedicmeet.appserver.pricing.OfferService;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VastuCompassServiceTest {

    @Test
    void categoriesUsesTwoHourCacheBeforeMongo() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        CacheService cache = mock(CacheService.class);
        Map<String, Object> cached = new LinkedHashMap<>();
        cached.put("list", java.util.List.of(Map.of("category", "Bedroom")));
        when(cache.get("vastu:category:list", Map.class)).thenReturn(cached);
        VastuCompassService service = new VastuCompassService(mongo, cache,
                mock(AppConstants.class), mock(MembershipService.class), mock(OfferService.class),
                mock(PushNotificationService.class), mock(CleverTapClient.class),
                "video", "info", "video-image", "info-image");

        assertSame(cached, service.categories());
        verify(mongo, never()).getCollection(org.mockito.ArgumentMatchers.anyString());
    }
}
