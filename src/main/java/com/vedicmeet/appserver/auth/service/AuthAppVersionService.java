package com.vedicmeet.appserver.auth.service;

import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Reproduces auths.js APP_DETAILS[platform][userType] (distinct from the /version DB route). */
@Service
public class AuthAppVersionService {

    private final String androidUser;
    private final String androidConsultant;
    private final String iosUser;
    private final String iosConsultant;

    public AuthAppVersionService(
            @Value("${ANDROID_USER_APP_VERSION:}") String androidUser,
            @Value("${ANDROID_CONS_APP_VERSION:}") String androidConsultant,
            @Value("${IOS_USER_APP_VERSION:}") String iosUser,
            @Value("${IOS_CONS_APP_VERSION:}") String iosConsultant) {
        this.androidUser = androidUser;
        this.androidConsultant = androidConsultant;
        this.iosUser = iosUser;
        this.iosConsultant = iosConsultant;
    }

    public Document details(String platform, String userType) {
        String version = "ios".equalsIgnoreCase(platform)
                ? ("cons".equals(userType) ? iosConsultant : iosUser)
                : ("cons".equals(userType) ? androidConsultant : androidUser);
        return new Document("APP_VERSION", version).append("TYPE_OF_UPDATE", "force");
    }
}
