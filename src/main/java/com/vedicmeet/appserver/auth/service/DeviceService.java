package com.vedicmeet.appserver.auth.service;

import com.vedicmeet.appserver.auth.cache.AuthDocumentCache;
import com.vedicmeet.appserver.auth.exception.AuthException;
import com.vedicmeet.appserver.auth.repository.AuthRepository;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import com.vedicmeet.appserver.security.Role;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Collection;

/** Atomic Java equivalent of user.js checkLoginDevice (limit defaults to 15). */
@Service
public class DeviceService {

    private final AuthRepository repository;
    private final AuthDocumentCache cache;
    private final int deviceLimit;

    public DeviceService(AuthRepository repository, AuthDocumentCache cache,
                         @Value("${vedicmeet.auth.device-limit:15}") int deviceLimit) {
        this.repository = repository;
        this.cache = cache;
        this.deviceLimit = deviceLimit;
    }

    public Document register(String role, Document account, String deviceToken, String fcmToken,
                             String deviceUuid, String deviceType, String voipToken) {
        ObjectId id = objectId(account);
        String collection = Role.CONSULTANT.equals(role) ? Collections.CONSULTANTS : Collections.USERS;
        Document updated = repository.registerDevice(collection, id, deviceToken, fcmToken,
                deviceUuid, deviceType, voipToken, deviceLimit);
        if (updated == null) throw new AuthException("DEVICE_LIMIT");
        cache.invalidate(role, account);
        return updated;
    }

    /** Send-OTP preflight mirrors the Node nested device.fcmToken count check. */
    public void enforcePreflight(Document account, String incomingToken) {
        if (account == null) return;
        Document device = account.get("device") instanceof Document d ? d : new Document();
        Object tokens = device.get("fcmToken");
        if (!(tokens instanceof Collection<?> collection)) return;
        boolean known = incomingToken != null && collection.stream()
                .anyMatch(value -> incomingToken.equals(String.valueOf(value)));
        if (!known && collection.size() >= deviceLimit) throw new AuthException("DEVICE_LIMIT");
    }

    private ObjectId objectId(Document account) {
        Object id = account == null ? null : account.get("_id");
        if (id instanceof ObjectId oid) return oid;
        if (id != null && ObjectId.isValid(String.valueOf(id))) return new ObjectId(String.valueOf(id));
        throw new AuthException("ACCOUNT_ID_INVALID");
    }
}
