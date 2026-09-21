package com.vedicmeet.appserver.security;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AuthUserServiceTest {

    @SuppressWarnings("unchecked")
    private Fixture fixture() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoCollection<Document> collection = mock(MongoCollection.class);
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(mongo.getCollection(Collections.USERS)).thenReturn(collection);
        when(collection.find(any(Document.class))).thenReturn(iterable);
        when(iterable.first()).thenReturn(new Document("_id", "user-1"));
        return new Fixture(new AuthUserService(mongo), collection);
    }

    @Test
    void httpUserLookupRequiresActiveAndNotDeleted() {
        Fixture f = fixture();
        f.service.loadForHttp(userPrincipal());

        ArgumentCaptor<Document> filter = ArgumentCaptor.forClass(Document.class);
        verify(f.collection).find(filter.capture());
        assertEquals(false, filter.getValue().get("isDeleted"));
        assertEquals(true, filter.getValue().get("status"));
    }

    @Test
    void socketUserLookupRejectsBlockedAccountsLikeHttpAuth() {
        Fixture f = fixture();
        f.service.loadForSocket(userPrincipal());

        ArgumentCaptor<Document> filter = ArgumentCaptor.forClass(Document.class);
        verify(f.collection).find(filter.capture());
        assertEquals(false, filter.getValue().get("isDeleted"));
        assertEquals(true, filter.getValue().get("status"));
    }

    private AuthPrincipal userPrincipal() {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("phone", "9000000001");
        claims.put("phonePrefix", "+91");
        claims.put("role", Role.USER);
        return new AuthPrincipal(claims);
    }

    private record Fixture(AuthUserService service, MongoCollection<Document> collection) { }
}
