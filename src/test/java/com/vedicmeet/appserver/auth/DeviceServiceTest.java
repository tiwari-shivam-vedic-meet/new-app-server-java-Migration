package com.vedicmeet.appserver.auth;

import com.vedicmeet.appserver.auth.cache.AuthDocumentCache;
import com.vedicmeet.appserver.auth.exception.AuthException;
import com.vedicmeet.appserver.auth.repository.AuthRepository;
import com.vedicmeet.appserver.auth.service.DeviceService;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import com.vedicmeet.appserver.security.Role;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeviceServiceTest {

    @Test
    void registerUsesAtomicRepositoryGateAndInvalidatesAuthCache() {
        AuthRepository repository = mock(AuthRepository.class);
        AuthDocumentCache cache = mock(AuthDocumentCache.class);
        DeviceService service = new DeviceService(repository, cache, 15);
        Document account = new Document("_id", new ObjectId());
        Document updated = new Document(account).append("deviceToken", List.of("d1"));
        when(repository.registerDevice(Collections.USERS, account.getObjectId("_id"),
                "d1", "f1", "u1", "android", "v1", 15)).thenReturn(updated);

        assertSame(updated, service.register(Role.USER, account,
                "d1", "f1", "u1", "android", "v1"));
        verify(cache).invalidate(Role.USER, account);
    }

    @Test
    void nullAtomicUpdateMeansDeviceLimit() {
        AuthRepository repository = mock(AuthRepository.class);
        DeviceService service = new DeviceService(repository, mock(AuthDocumentCache.class), 15);
        Document account = new Document("_id", new ObjectId());

        AuthException error = assertThrows(AuthException.class, () -> service.register(
                Role.USER, account, "new", "fcm", null, "android", null));
        assertEquals("DEVICE_LIMIT", error.getMessage());
    }

    @Test
    void otpPreflightAllowsKnownTokenAndRejectsSixteenthDevice() {
        DeviceService service = new DeviceService(mock(AuthRepository.class),
                mock(AuthDocumentCache.class), 15);
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < 15; i++) tokens.add("token-" + i);
        Document account = new Document("device", new Document("fcmToken", tokens));

        service.enforcePreflight(account, "token-4");
        assertThrows(AuthException.class, () -> service.enforcePreflight(account, "token-new"));
    }
}
