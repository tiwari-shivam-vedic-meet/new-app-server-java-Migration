package com.vedicmeet.appserver.auth;

import com.vedicmeet.appserver.auth.cache.AuthDocumentCache;
import com.vedicmeet.appserver.auth.dto.AuthRequests;
import com.vedicmeet.appserver.auth.exception.AuthException;
import com.vedicmeet.appserver.auth.repository.AuthRepository;
import com.vedicmeet.appserver.auth.service.ConsultantRegistrationService;
import com.vedicmeet.appserver.auth.service.RegistrationIntegrationService;
import com.vedicmeet.appserver.auth.validation.AuthRequestValidator;
import com.vedicmeet.appserver.security.Role;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConsultantRegistrationServiceTest {

    @Test
    void duplicateMobileIsRejectedBeforeAnyWrite() {
        Fixture f = fixture();
        AuthRequests.ConsultantSignupRequest request = request();
        when(f.repository.findConsultantByPhone("9000000002", false)).thenReturn(new Document());

        AuthException error = assertThrows(AuthException.class,
                () -> f.service.signUp(request, null));

        assertEquals("MOBILE_NUMBER_EXIST", error.getMessage());
        verify(f.repository, never()).insertConsultant(any());
    }

    @Test
    void unknownReferralIsRejectedBeforeCreatingConsultant() {
        Fixture f = fixture();
        AuthRequests.ConsultantSignupRequest request = request();
        request.referCode = "UNKNOWN";

        AuthException error = assertThrows(AuthException.class,
                () -> f.service.signUp(request, null));

        assertEquals("REFER_NOT_VALID", error.getMessage());
        verify(f.repository, never()).insertConsultant(any());
    }

    @Test
    void signupCreatesPendingConsultantWalletBoostReferralAndNotification() {
        Fixture f = fixture();
        AuthRequests.ConsultantSignupRequest request = request();
        request.referCode = "REFER1";
        Document referrer = new Document("_id", new ObjectId()).append("referId", "REFER1");
        ObjectId consultantId = new ObjectId();
        when(f.repository.findConsultantByReferCode("REFER1")).thenReturn(referrer);
        when(f.repository.generateReferCode(4)).thenReturn("VEDI12");
        when(f.repository.nextConsultantId()).thenReturn("26SEP1");
        when(f.repository.insertConsultant(any())).thenAnswer(invocation -> {
            Document consultant = invocation.getArgument(0);
            consultant.put("_id", consultantId);
            return consultant;
        });

        Document result = f.service.signUp(request, "profiles/test.png");

        assertFalse(result.getBoolean("isAdminVerify"));
        assertEquals("offline", result.getString("isActive"));
        assertEquals("profiles/test.png", result.getString("image"));
        assertEquals("9000000002", result.get("details", Document.class).getString("phone"));
        verify(f.repository).createWalletForConsultant(consultantId);
        verify(f.repository).createConsultantBoost(consultantId);
        verify(f.repository).createConsultantReferral(
                referrer.getObjectId("_id"), consultantId, "REFER1");
        verify(f.integrations).consultantRegistered(result, "fcm-cons");
    }

    @Test
    void approvalDelegatesNodeCompatibleApproveOrDeleteDecisionAndInvalidatesCache() {
        Fixture f = fixture();
        ObjectId id = new ObjectId();
        Document existing = new Document("_id", id).append("details",
                new Document("phone", "9000000002").append("phonePrefix", "91"));
        Document approved = new Document(existing).append("isAdminVerify", true);
        when(f.repository.findConsultantById(id)).thenReturn(existing);
        when(f.repository.approveConsultant(id, true)).thenReturn(approved);

        assertEquals(approved, f.service.approve(id.toHexString(), "true"));
        verify(f.repository).approveConsultant(id, true);
        verify(f.cache).invalidate(Role.CONSULTANT, existing);
    }

    private Fixture fixture() {
        AuthRepository repository = mock(AuthRepository.class);
        RegistrationIntegrationService integrations = mock(RegistrationIntegrationService.class);
        AuthDocumentCache cache = mock(AuthDocumentCache.class);
        ConsultantRegistrationService service = new ConsultantRegistrationService(
                repository, new AuthRequestValidator(), integrations, cache);
        return new Fixture(service, repository, integrations, cache);
    }

    private AuthRequests.ConsultantSignupRequest request() {
        AuthRequests.ConsultantSignupRequest request = new AuthRequests.ConsultantSignupRequest();
        request.name = "Consultant";
        request.userName = "consultant-one";
        request.email = "CONSULTANT@example.com";
        request.mobile = "9000000002";
        request.countryCode = "91";
        request.fcmToken = "fcm-cons";
        return request;
    }

    private record Fixture(ConsultantRegistrationService service, AuthRepository repository,
                           RegistrationIntegrationService integrations, AuthDocumentCache cache) { }
}
