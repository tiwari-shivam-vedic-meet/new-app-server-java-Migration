package com.vedicmeet.appserver.social;

import com.vedicmeet.appserver.config.AppConstants;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates the FAITHFUL FOLLOWLIST lookup pipelines of {@link SocialWriteService} parse as valid Mongo
 * aggregation stages and substitute the media URL (Node user.js followCons FOLLOWLIST L3316-3399).
 */
class SocialWriteServiceFollowListTest {

    private final AppConstants constants = new AppConstants("vedic-meet-bucket", "ap-south-1");
    private final SocialWriteService svc = new SocialWriteService(null, constants);

    @Test
    void consultantLookup_parses_andSubstitutesMediaUrl_andKeepsUserNameCond() {
        Document lookup = svc.followListConsultantLookup();
        assertTrue(lookup.containsKey("$lookup"));
        Document inner = (Document) lookup.get("$lookup");
        assertEquals("consultants", inner.getString("from"));
        String json = lookup.toJson();
        assertTrue(json.contains("https://vedic-meet-bucket.s3.ap-south-1.amazonaws.com/"),
                "MEDIA_URL substituted");
        assertFalse(json.contains("__MEDIA_URL__"));
        assertTrue(json.contains("userName"), "userName $cond preserved");
    }

    @Test
    void userLookup_parses_andKeepsNodeEmialTypo() {
        Document lookup = svc.followListUserLookup();
        Document inner = (Document) lookup.get("$lookup");
        assertEquals("users", inner.getString("from"));
        assertTrue(lookup.toJson().contains("emial"), "Node's 'emial' typo preserved faithfully");
    }
}
