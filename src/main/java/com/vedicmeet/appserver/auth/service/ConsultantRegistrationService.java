package com.vedicmeet.appserver.auth.service;

import com.vedicmeet.appserver.auth.cache.AuthDocumentCache;
import com.vedicmeet.appserver.auth.dto.AuthRequests;
import com.vedicmeet.appserver.auth.exception.AuthException;
import com.vedicmeet.appserver.auth.repository.AuthRepository;
import com.vedicmeet.appserver.auth.validation.AuthRequestValidator;
import com.vedicmeet.appserver.security.Role;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/** Port of consultant-service.js signUpConsultant and consultant.js approveConsultant. */
@Service
public class ConsultantRegistrationService {

    private final AuthRepository repository;
    private final AuthRequestValidator validator;
    private final RegistrationIntegrationService integrations;
    private final AuthDocumentCache cache;

    public ConsultantRegistrationService(AuthRepository repository,
                                         AuthRequestValidator validator,
                                         RegistrationIntegrationService integrations,
                                         AuthDocumentCache cache) {
        this.repository = repository;
        this.validator = validator;
        this.integrations = integrations;
        this.cache = cache;
    }

    @Transactional
    public Document signUp(AuthRequests.ConsultantSignupRequest request, String uploadedProfileImage) {
        validator.consultantSignup(request);
        String email = validator.email(request.email);
        String phone = firstNonBlank(request.phone, request.mobile);
        String prefix = firstNonBlank(request.phonePrefix, request.countryCode);

        Document referrer = null;
        if (notBlank(request.referCode)) {
            referrer = repository.findConsultantByReferCode(request.referCode);
            if (referrer == null) throw new AuthException("REFER_NOT_VALID");
        }
        if (notBlank(phone) && repository.findConsultantByPhone(phone, false) != null) {
            throw new AuthException("MOBILE_NUMBER_EXIST");
        }
        if (repository.findConsultantByEmail(email, false) != null) {
            throw new AuthException("EMAIL_EXIST");
        }
        if (notBlank(request.userName) && repository.findConsultantByUsername(request.userName) != null) {
            throw new AuthException("USER_NAME_EXIST");
        }

        Document consultant = buildDocument(request, email, phone, prefix, uploadedProfileImage);
        repository.insertConsultant(consultant);
        ObjectId consultantId = objectId(consultant);
        repository.createWalletForConsultant(consultantId);
        repository.createConsultantBoost(consultantId);
        if (referrer != null) {
            repository.createConsultantReferral(objectId(referrer), consultantId, request.referCode);
        }
        afterCommit(() -> integrations.consultantRegistered(consultant, request.fcmToken));
        return consultant;
    }

    @Transactional
    public Document approve(String consultantId, Object approve) {
        if (!ObjectId.isValid(consultantId)) throw new AuthException("CONSULTANT_NOT_EXIST");
        ObjectId id = new ObjectId(consultantId);
        Document existing = repository.findConsultantById(id);
        if (existing == null) throw new AuthException("CONSULTANT_NOT_EXIST");
        boolean accepted = booleanValue(approve);
        Document changed = repository.approveConsultant(id, accepted);
        cache.invalidate(Role.CONSULTANT, existing);
        return changed;
    }

    private Document buildDocument(AuthRequests.ConsultantSignupRequest request, String email,
                                   String phone, String prefix, String uploadedProfileImage) {
        Document consultant = new Document(new LinkedHashMap<>(request.extra));
        put(consultant, "name", request.name);
        put(consultant, "userName", request.userName);
        put(consultant, "email", email); // root alias is retained for the legacy email-login path
        put(consultant, "mobile", phone);
        put(consultant, "countryCode", prefix);
        put(consultant, "gender", request.gender);
        put(consultant, "dob", firstNonBlank(request.dob, request.dateOfBirth));
        put(consultant, "dateOfBirth", request.dateOfBirth);
        put(consultant, "consType", request.consType);
        put(consultant, "language", request.language);
        put(consultant, "primarySkills", request.primarySkills);
        put(consultant, "otherSkills", request.otherSkills);
        put(consultant, "expertise", request.expertise);
        put(consultant, "problems", request.problems);
        put(consultant, "avgLiveBrier", request.avgLiveBrier);
        put(consultant, "deviceToken", request.deviceToken);
        put(consultant, "fcmToken", request.fcmToken);
        put(consultant, "address", request.address);
        put(consultant, "city", request.city);
        put(consultant, "state", request.state);
        put(consultant, "country", request.country);
        put(consultant, "pincode", request.pincode);
        put(consultant, "referCode", request.referCode);
        put(consultant, "reasonOfOnboard", request.reasonOfOnboard);
        put(consultant, "mainSourceIncome", request.mainSourceIncome);
        put(consultant, "qualification", request.qualification);
        put(consultant, "highestQualification", request.highestQualification);
        put(consultant, "learnAstrologyFrom", request.learnAstrologyFrom);
        put(consultant, "instaLink", request.instaLink);
        put(consultant, "faceBookLink", request.faceBookLink);
        put(consultant, "linkedinLink", request.linkedinLink);
        put(consultant, "youTubeLink", request.youTubeLink);
        put(consultant, "foreignCountryNo", request.foreignCountryNo);
        put(consultant, "workingFullTimeJob", request.workingFullTimeJob);
        put(consultant, "greaterChallengeAndConquer", request.greaterChallengeAndConquer);
        put(consultant, "bio", request.bio);
        put(consultant, "isRefer", request.isRefer);
        put(consultant, "experienceYear", request.experienceYear);
        put(consultant, "dailyWorkHour", request.dailyWorkHour);
        put(consultant, "hearAboutUs", request.hearAboutUs);
        put(consultant, "otherOnlinePlatformWork", request.otherOnlinePlatformWork);
        put(consultant, "minimumEarningExpectation", request.minimumEarningExpectation);
        put(consultant, "price", request.price);
        put(consultant, "whenHearAboutUs", request.whenHearAboutUs);
        put(consultant, "onlinePlatformWork", request.onlinePlatformWork);
        put(consultant, "onlinePlatformName", request.onlinePlatformName);
        if (notBlank(uploadedProfileImage)) consultant.put("image", uploadedProfileImage);

        Document details = new Document("phone", phone).append("email", email)
                .append("phonePrefix", prefix).append("gender", request.gender)
                .append("dob", firstNonBlank(request.dob, request.dateOfBirth))
                .append("problems", request.problems).append("address", request.address)
                .append("city", request.city).append("state", request.state)
                .append("country", request.country).append("zip", request.pincode);
        consultant.put("details", details);
        consultant.put("device", new Document("fcmToken", new ArrayList<>())
                .append("uuid", new ArrayList<>()).append("lastDevice", ""));
        consultant.put("score", intValue(request.score));
        consultant.put("referId", repository.generateReferCode(4));
        consultant.put("lastPriceChangeDate", Date.from(Instant.now()));
        consultant.put("userId", repository.nextConsultantId());
        consultant.put("isAdminVerify", false);
        consultant.put("status", true);
        consultant.put("isDeleted", false);
        consultant.put("isActive", "offline");
        consultant.put("certificate", new ArrayList<>());
        consultant.put("authTokenVersion", 0);
        return consultant;
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) return bool;
        if (value != null && ("true".equalsIgnoreCase(String.valueOf(value))
                || "false".equalsIgnoreCase(String.valueOf(value)))) {
            return Boolean.parseBoolean(String.valueOf(value));
        }
        throw new AuthException("approve is required");
    }

    private int intValue(Object value) {
        if (value instanceof Number number) return number.intValue();
        try { return value == null || String.valueOf(value).isBlank() ? 0 : Integer.parseInt(String.valueOf(value)); }
        catch (NumberFormatException error) { throw new AuthException("score must be a number"); }
    }

    private ObjectId objectId(Document document) {
        Object value = document == null ? null : document.get("_id");
        if (value instanceof ObjectId oid) return oid;
        if (value != null && ObjectId.isValid(String.valueOf(value))) return new ObjectId(String.valueOf(value));
        throw new AuthException("CONSULTANT_NOT_EXIST");
    }

    private void put(Document document, String key, Object value) { if (value != null) document.put(key, value); }
    private String firstNonBlank(String first, String second) { return notBlank(first) ? first : second; }
    private boolean notBlank(String value) { return value != null && !value.isBlank(); }
    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { action.run(); }
        });
    }
}
