package com.vedicmeet.appserver.auth.service;

import com.vedicmeet.appserver.auth.cache.AuthDocumentCache;
import com.vedicmeet.appserver.auth.dto.AuthRequests;
import com.vedicmeet.appserver.auth.exception.AuthException;
import com.vedicmeet.appserver.auth.provider.OtpProvider;
import com.vedicmeet.appserver.auth.provider.SocialIdentityVerifier;
import com.vedicmeet.appserver.auth.repository.AuthRepository;
import com.vedicmeet.appserver.auth.validation.AuthRequestValidator;
import com.vedicmeet.appserver.config.AppConstants;
import com.vedicmeet.appserver.security.AuthPrincipal;
import com.vedicmeet.appserver.security.Role;
import com.vedicmeet.appserver.security.TokenRevocationService;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Complete mobile user/consultant authentication orchestration ported from auths.js + user.js. */
@Service
public class UserAuthService {

    private final AuthRepository repository;
    private final AuthRequestValidator validator;
    private final OtpAuthService otp;
    private final DeviceService devices;
    private final AuthTokenFactory tokens;
    private final SocialIdentityVerifier socialVerifier;
    private final RegistrationIntegrationService integrations;
    private final AuthDocumentCache authCache;
    private final TokenRevocationService revocations;
    private final ZodiacCalculator zodiac;
    private final AppConstants constants;
    private final AuthAppVersionService authAppVersions;
    private final boolean legacyEmailLoginEnabled;

    public UserAuthService(
            AuthRepository repository,
            AuthRequestValidator validator,
            OtpAuthService otp,
            DeviceService devices,
            AuthTokenFactory tokens,
            SocialIdentityVerifier socialVerifier,
            RegistrationIntegrationService integrations,
            AuthDocumentCache authCache,
            TokenRevocationService revocations,
            ZodiacCalculator zodiac,
            AppConstants constants,
            AuthAppVersionService authAppVersions,
            @Value("${vedicmeet.auth.legacy-email-login-enabled:true}") boolean legacyEmailLoginEnabled) {
        this.repository = repository;
        this.validator = validator;
        this.otp = otp;
        this.devices = devices;
        this.tokens = tokens;
        this.socialVerifier = socialVerifier;
        this.integrations = integrations;
        this.authCache = authCache;
        this.revocations = revocations;
        this.zodiac = zodiac;
        this.constants = constants;
        this.authAppVersions = authAppVersions;
        this.legacyEmailLoginEnabled = legacyEmailLoginEnabled;
    }

    @Transactional
    public Map<String, Object> verifyUserOtp(AuthRequests.VerifyOtpRequest request) {
        OtpProvider.OtpResult verified = otp.verify(request, "user");
        if (!verified.success()) throw new AuthException(
                verified.message() == null ? "Invalid OTP" : verified.message());
        String phone = validator.phone(request);
        String prefix = validator.phonePrefix(request);
        Document user = repository.findUserByPhone(phone, prefix, false);
        Map<String, Object> data = new LinkedHashMap<>();
        if (user == null) {
            data.put("isNewUser", true);
            return data;
        }
        requireUserActive(user);
        repository.ensureFamilyCommunityMapping(objectId(user));
        data.put("isNewUser", false);
        data.put("token", tokens.accountToken(Role.USER, user));
        data.put("user", user);
        return data;
    }

    public Map<String, Object> verifyConsultantOtp(AuthRequests.VerifyOtpRequest request) {
        validator.verifyOtp(request);
        Document consultant = null;
        if (!Boolean.TRUE.equals(request.isSecondaryNumber)) {
            // Node checks account existence before calling MSG91 for a primary consultant number.
            consultant = repository.findConsultantByPhone(
                    validator.phone(request), validator.phonePrefix(request), false);
            if (consultant == null) throw AuthException.badRequest("Invalid phone number");
            requireConsultantActive(consultant);
        }
        OtpProvider.OtpResult verified = otp.verify(request, "consultant");
        if (!verified.success()) throw AuthException.badRequest("Invalid OTP");
        if (Boolean.TRUE.equals(request.isSecondaryNumber)) return new LinkedHashMap<>();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", tokens.accountToken(Role.CONSULTANT, consultant));
        data.put("consultant", consultant);
        return data;
    }

    @Transactional
    public RegistrationResult registerUser(AuthRequests.UserRegistrationRequest request, String platform) {
        validator.register(request);
        String phone = validator.phone(request);
        String prefix = validator.phonePrefix(request);
        String email = request.email == null || request.email.isBlank() ? null : validator.email(request.email);
        otp.requireRegistrationProof(prefix, phone);

        Document existing = repository.findUserByPhone(phone, prefix, false);
        if (existing == null && email != null) existing = repository.findUserByEmail(email, false);
        if (existing != null) {
            requireUserActive(existing);
            return new RegistrationResult(true, tokens.accountToken(Role.USER, existing), existing);
        }

        Document user = buildRegisteredUser(request, platform, phone, prefix, email);
        repository.insertUser(user);
        ObjectId id = objectId(user);
        repository.createWalletForUser(id);
        repository.ensureFamilyCommunityMapping(id);

        Map<String, Object> welcomeData = Map.of("userId", String.valueOf(user.get("userId")));
        safely(() -> repository.saveNotification(id, "Vedic Meet", "Welcome to Vedic Meet", welcomeData));
        safely(() -> repository.saveNotification(id, "Vedic Meet", "Welcome coupon added", welcomeData));
        Map<String, Object> registrationTracking = tracking(request);
        afterCommit(() -> integrations.userRegistered(user, request.fcmToken, registrationTracking));
        return new RegistrationResult(false, tokens.accountToken(Role.USER, user), user);
    }

    @Transactional
    public Map<String, Object> login(AuthRequests.LoginRequest request) {
        validator.login(request);
        boolean isNew = false;
        Document account;
        String role;

        if ("user".equals(request.userType)) {
            role = Role.USER;
            if (notBlank(request.email)) {
                if (!legacyEmailLoginEnabled) throw AuthException.unauthorized("EMAIL_LOGIN_REQUIRES_VERIFICATION");
                String email = validator.email(request.email);
                account = repository.findUserByEmail(email, false);
                if (account == null) {
                    account = repository.insertUser(buildMinimalUser(request, email, null, null));
                    repository.createWalletForUser(objectId(account));
                    repository.ensureFamilyCommunityMapping(objectId(account));
                    isNew = true;
                }
            } else {
                AuthRequests.VerifyOtpRequest verify = verificationRequest(request);
                OtpProvider.OtpResult result = otp.verify(verify, "user");
                if (!result.success()) throw new AuthException("INVALID_OTP");
                String phone = validator.phone(request);
                String prefix = validator.phonePrefix(request);
                account = repository.findUserByPhone(phone, prefix, false);
                if (account == null) {
                    account = repository.insertUser(buildMinimalUser(request, null, phone, prefix));
                    repository.createWalletForUser(objectId(account));
                    repository.ensureFamilyCommunityMapping(objectId(account));
                    isNew = true;
                }
            }
            requireUserActive(account);
        } else {
            role = Role.CONSULTANT;
            if (notBlank(request.email)) {
                if (!legacyEmailLoginEnabled) throw AuthException.unauthorized("EMAIL_LOGIN_REQUIRES_VERIFICATION");
                account = repository.findConsultantByEmail(validator.email(request.email), false);
                if (account == null) throw new AuthException("EMAIL_NOT_EXIST");
            } else {
                AuthRequests.VerifyOtpRequest verify = verificationRequest(request);
                OtpProvider.OtpResult result = otp.verify(verify, "consultant");
                if (!result.success()) throw new AuthException("INVALID_OTP");
                account = repository.findConsultantByPhone(
                        validator.phone(request), validator.phonePrefix(request), false);
                if (account == null) throw new AuthException("MOBILE_NOT_NUMBER_EXIST");
                repository.markConsultantPrimaryPhoneVerified(objectId(account));
            }
            requireConsultantActive(account);
        }

        account = devices.register(role, account, request.deviceToken, request.fcmToken,
                request.deviceUUID, request.deviceType, request.voipToken);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("accessToken", tokens.accountToken(role, account));
        response.put("isUserNew", isNew);
        return response;
    }

    public Map<String, Object> socialLogin(AuthRequests.SocialLoginRequest request) {
        validator.social(request);
        SocialIdentityVerifier.SocialIdentity identity = socialVerifier.verify(request);
        Document account;
        if ("user".equals(request.userType)) {
            account = repository.findUserByEmail(identity.email(), false);
            if (account == null) return socialNewUser();
            requireUserActive(account);
            return socialExisting("user", Role.USER, account);
        }
        account = repository.findConsultantByEmail(identity.email(), false);
        if (account == null) return socialNewUser();
        requireConsultantActive(account);
        return socialExisting("consultant", Role.CONSULTANT, account);
    }

    @Transactional
    public void logout(AuthRequests.LogoutRequest request, AuthPrincipal principal, Document account) {
        validator.logout(request);
        String expected = Role.CONSULTANT.equals(principal.getRole()) ? "cons" : "user";
        if (!expected.equals(request.userType)) throw new AuthException("USER_TYPE_INVALID");
        String collection = Role.CONSULTANT.equals(principal.getRole())
                ? AppConstants.Collections.CONSULTANTS : AppConstants.Collections.USERS;
        Document updated = repository.logout(collection, objectId(account), request.logOutFrom,
                request.fcmToken, request.deviceToken);
        authCache.invalidate(principal.getRole(), account);
        revocations.revoke(principal);
        if ("all".equals(request.logOutFrom) && updated != null) {
            authCache.invalidate(principal.getRole(), updated);
        }
    }

    @Transactional
    public Map<String, Object> deleteAccount(String requestedType, AuthPrincipal principal, Document account) {
        String expected = Role.CONSULTANT.equals(principal.getRole()) ? "cons" : "user";
        if (!expected.equals(requestedType)) throw new AuthException("USER_TYPE_INVALID");
        ObjectId id = objectId(account);
        if (Role.USER.equals(principal.getRole())) {
            repository.softDeleteUser(id);
            repository.cancelUserWaitlists(id);
        } else {
            repository.softDeleteConsultant(id);
            repository.cancelConsultantRequests(id);
        }
        authCache.invalidate(principal.getRole(), account);
        revocations.revoke(principal);
        return Map.of("success", true, "message", "Account deleted successfully");
    }

    public Document registerDevice(AuthRequests.DeviceRegistrationRequest request,
                                   AuthPrincipal principal, Document account) {
        String expected = Role.CONSULTANT.equals(principal.getRole()) ? "cons" : "user";
        if (!expected.equals(request.userType)) throw new AuthException("USER_TYPE_INVALID");
        if (!notBlank(request.deviceToken) || !notBlank(request.fcmToken)) {
            throw new AuthException("deviceToken and fcmToken are required");
        }
        return devices.register(principal.getRole(), account, request.deviceToken, request.fcmToken,
                request.deviceUUID, request.deviceType, request.voipToken);
    }

    public Map<String, Object> verifyCurrent(AuthPrincipal principal, String platform, String userType) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("appDetails", authAppVersions.details(platform, userType));
        if (principal == null) return data;
        Document account = null;
        if (principal.getPhone() != null) {
            account = Role.CONSULTANT.equals(principal.getRole())
                    ? repository.findConsultantByPhone(principal.getPhone(), principal.getPhonePrefix(), false)
                    : repository.findUserByPhone(principal.getPhone(), principal.getPhonePrefix(), false);
        }
        if (account == null && principal.getEmail() != null) {
            account = Role.CONSULTANT.equals(principal.getRole())
                    ? repository.findConsultantByEmail(principal.getEmail(), false)
                    : repository.findUserByEmail(principal.getEmail(), false);
        }
        if (account == null) throw new AuthException(
                Role.CONSULTANT.equals(principal.getRole()) ? "Consultant not found" : "User not found");
        if (Role.CONSULTANT.equals(principal.getRole())) requireConsultantActive(account);
        else requireUserActive(account);
        data.put(Role.CONSULTANT.equals(principal.getRole()) ? "consultant" : "user",
                withMediaUrl(account));
        return data;
    }

    public Document details(String userType, AuthPrincipal principal, Document account) {
        validator.userType(userType);
        String expected = Role.CONSULTANT.equals(principal.getRole()) ? "cons" : "user";
        if (!expected.equals(userType)) throw new AuthException("USER_TYPE_INVALID");
        String expectedCollectionRole = "cons".equals(userType) ? Role.CONSULTANT : Role.USER;
        Document fresh = Role.CONSULTANT.equals(expectedCollectionRole)
                ? repository.findConsultantById(account.get("_id")) : repository.findUserById(account.get("_id"));
        if (fresh == null) throw new AuthException("User not found");
        if (Role.USER.equals(expectedCollectionRole) && fresh.get("userId") != null) {
            fresh.put("referId", fresh.get("userId"));
        }
        return fresh;
    }

    public Document appVersion(String userType, String deviceType) {
        validator.userType(userType);
        if (!"ios".equals(deviceType) && !"android".equals(deviceType)) throw new AuthException("deviceType is invalid");
        return repository.appVersion(userType, deviceType);
    }

    public Document authAppDetails(String platform, String userType) {
        if (!"user".equals(userType) && !"cons".equals(userType)) return new Document();
        return authAppVersions.details(platform, userType);
    }

    public long completedConsultationsForDevice(String deviceUuid) {
        if (!notBlank(deviceUuid)) throw new AuthException("deviceUUID is required");
        return repository.completedConsultationsForDevice(deviceUuid);
    }

    private Document buildRegisteredUser(AuthRequests.UserRegistrationRequest request, String platform,
                                         String phone, String prefix, String email) {
        List<String> fcm = notBlank(request.fcmToken) ? List.of(request.fcmToken) : new ArrayList<>();
        List<String> uuid = notBlank(request.deviceUUID) ? List.of(request.deviceUUID) : new ArrayList<>();
        Document details = new Document("email", email).append("phone", phone).append("phonePrefix", prefix)
                .append("gender", request.gender).append("dob", request.dob)
                .append("timeOfBirth", formatTimeOfBirth(request.timeOfBirth)).append("placeOfBirth", request.placeOfBirth)
                .append("placeLatLong", request.placeLatLong).append("problems", request.problems)
                .append("address", null).append("city", null).append("state", null)
                .append("country", null).append("zip", null);
        Document availability = new Document();
        for (String day : List.of("sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday")) {
            availability.put(day, new ArrayList<>());
        }
        return new Document("name", notBlank(request.name) ? request.name : "user")
                .append("isAvailabilityCopyToWeekly", true).append("isAvailabilityCopyToMonthly", false)
                .append("device", new Document("fcmToken", fcm).append("deviceToken", new ArrayList<>())
                        .append("uuid", uuid).append("lastDevice", ""))
                .append("details", details).append("availability", availability)
                .append("referId", repository.generateReferCode(4)).append("userId", repository.nextUserId())
                .append("receivePromotionAndOffers", false).append("status", true).append("isDeleted", false)
                .append("voipToken", "").append("deviceType", notBlank(platform) ? platform : "android")
                .append("bonusConsultantCoupon", new Document("newCouponCode", "")
                        .append("previousCouponCode", "").append("timeRemaining", 0).append("totalLastOrder", 0))
                .append("isActive", "online").append("isVerified", true)
                .append("zodiac", zodiac.from(request.dob)).append("todayStreak", false).append("streak", 0)
                .append("wallet", 0).append("savedAmount", 0).append("logs", new Document("app", new ArrayList<>()))
                .append("subscription", "none").append("profileImage", null).append("authTokenVersion", 0);
    }

    private Document buildMinimalUser(AuthRequests.LoginRequest request, String email, String phone, String prefix) {
        Document details = new Document("email", email).append("phone", phone).append("phonePrefix", prefix);
        return new Document("name", "user").append("email", email).append("details", details)
                .append("referId", repository.generateReferCode(4)).append("userId", repository.nextUserId())
                .append("device", new Document("fcmToken", new ArrayList<>()).append("deviceToken", new ArrayList<>())
                        .append("uuid", new ArrayList<>()).append("lastDevice", ""))
                .append("deviceToken", new ArrayList<>()).append("fcmToken", new ArrayList<>())
                .append("status", true).append("isDeleted", false).append("isVerified", true)
                .append("wallet", 0).append("savedAmount", 0).append("authTokenVersion", 0)
                .append("deviceType", request.deviceType).append("voipToken", request.voipToken == null ? "" : request.voipToken);
    }

    private AuthRequests.VerifyOtpRequest verificationRequest(AuthRequests.LoginRequest login) {
        AuthRequests.VerifyOtpRequest request = new AuthRequests.VerifyOtpRequest();
        request.phone = login.phone;
        request.mobile = login.mobile;
        request.phonePrefix = login.phonePrefix;
        request.countryCode = login.countryCode;
        request.otp = login.otp;
        request.userType = login.userType;
        return request;
    }

    private Map<String, Object> socialNewUser() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("isUserNew", true);
        data.put("token", "");
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("isUserNew", true);
        response.put("code", 200);
        response.put("message", "Social login successfully");
        response.put("data", data);
        return response;
    }

    private Map<String, Object> socialExisting(String key, String role, Document account) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("isUserNew", false);
        data.put("token", tokens.accountToken(role, account));
        data.put(key, account);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("isUserNew", false);
        response.put("code", 200);
        response.put("message", "Social login successfully");
        response.put("data", data);
        return response;
    }

    private Map<String, Object> tracking(AuthRequests.UserRegistrationRequest request) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("Source", notBlank(request.source) ? request.source : "direct");
        putIfPresent(data, "UTM_Source", request.utm_source);
        putIfPresent(data, "UTM_Medium", request.utm_medium);
        putIfPresent(data, "UTM_Campaign", request.utm_campaign);
        putIfPresent(data, "UTM_Term", request.utm_term);
        putIfPresent(data, "UTM_Content", request.utm_content);
        return data;
    }

    private Document withMediaUrl(Document source) {
        Document result = new Document(source);
        Object image = result.get("profileImage");
        if (image != null && !String.valueOf(image).startsWith("https")) {
            result.put("profileImage", constants.mediaUrl + image);
        }
        return result;
    }

    private void requireUserActive(Document user) {
        if (Boolean.TRUE.equals(user.get("isDeleted"))) throw new AuthException("EMAIL_NOT_EXIST");
        if (Boolean.FALSE.equals(user.get("status"))) throw new AuthException("ACCOUNT_BLOCK");
    }

    private void requireConsultantActive(Document consultant) {
        if (Boolean.TRUE.equals(consultant.get("isDeleted"))) throw new AuthException("CONSULTANT_NOT_EXIST");
        if (!Boolean.TRUE.equals(consultant.get("isAdminVerify"))) throw new AuthException("CONSULTANT_NOT_VERIFY_BY_ADMIN");
        if (Boolean.FALSE.equals(consultant.get("status"))) throw new AuthException("ACCOUNT_BLOCK");
    }

    private ObjectId objectId(Document document) {
        Object value = document == null ? null : document.get("_id");
        if (value instanceof ObjectId oid) return oid;
        if (value != null && ObjectId.isValid(String.valueOf(value))) return new ObjectId(String.valueOf(value));
        throw new AuthException("ACCOUNT_ID_INVALID");
    }

    private boolean notBlank(String value) { return value != null && !value.isBlank(); }
    private String formatTimeOfBirth(String value) {
        if (!notBlank(value)) return null;
        try {
            Instant instant;
            try { instant = Instant.parse(value); }
            catch (RuntimeException ignored) { instant = OffsetDateTime.parse(value).toInstant(); }
            return DateTimeFormatter.ofPattern("h:mm a")
                    .withZone(ZoneId.of("Asia/Kolkata"))
                    .format(instant);
        } catch (RuntimeException ignored) {
            // Preserve a legacy non-ISO value rather than silently dropping client data.
            return value;
        }
    }
    private void putIfPresent(Map<String, Object> target, String key, String value) { if (value != null) target.put(key, value); }
    private void safely(Runnable action) { try { action.run(); } catch (RuntimeException ignored) { } }
    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            safely(action);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { safely(action); }
        });
    }

    public record RegistrationResult(boolean existing, String token, Document user) { }
}
