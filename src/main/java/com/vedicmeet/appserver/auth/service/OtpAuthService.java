package com.vedicmeet.appserver.auth.service;

import com.vedicmeet.appserver.auth.dto.AuthRequests;
import com.vedicmeet.appserver.auth.exception.AuthException;
import com.vedicmeet.appserver.auth.provider.OtpProvider;
import com.vedicmeet.appserver.auth.repository.AuthRepository;
import com.vedicmeet.appserver.auth.validation.AuthRequestValidator;
import com.vedicmeet.appserver.cache.CacheService;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Phone OTP policy + provider orchestration, without the Node hard-coded OTP bypass. */
@Service
public class OtpAuthService {

    private static final String PROOF_PREFIX = "auth:otp-verified:";

    private final AuthRepository repository;
    private final AuthRequestValidator validator;
    private final DeviceService devices;
    private final OtpProvider provider;
    private final CacheService cache;
    private final long proofTtlSeconds;
    private final boolean proofRequiredForRegister;

    public OtpAuthService(AuthRepository repository, AuthRequestValidator validator,
                          DeviceService devices, OtpProvider provider, CacheService cache,
                          @Value("${vedicmeet.auth.otp-proof-ttl-seconds:600}") long proofTtlSeconds,
                          @Value("${vedicmeet.auth.otp-proof-required-for-register:false}") boolean proofRequiredForRegister) {
        this.repository = repository;
        this.validator = validator;
        this.devices = devices;
        this.provider = provider;
        this.cache = cache;
        this.proofTtlSeconds = proofTtlSeconds;
        this.proofRequiredForRegister = proofRequiredForRegister;
    }

    public OtpProvider.OtpResult send(AuthRequests.SendOtpRequest request) {
        validator.sendOtp(request);
        String phone = validator.phone(request);
        String prefix = validator.phonePrefix(request);
        if ("user".equals(request.userType)) {
            Document user = repository.findUserByPhone(phone, prefix, false);
            requireActive(user);
            devices.enforcePreflight(user, request.deviceToken);
        } else if (request.isSecondaryNumber == null || request.isSecondaryNumber.isBlank()) {
            Document consultant = repository.findConsultantByPhone(phone, prefix, false);
            if (consultant == null) throw new AuthException("CONSULTANT_NOT_EXIST");
            requireConsultantActive(consultant);
            devices.enforcePreflight(consultant, request.deviceToken);
        }
        return provider.send(prefix, phone);
    }

    public OtpProvider.OtpResult resend(AuthRequests.SendOtpRequest request) {
        validator.sendOtp(request);
        return provider.resend(validator.phonePrefix(request), validator.phone(request));
    }

    public OtpProvider.OtpResult verify(AuthRequests.VerifyOtpRequest request, String proofRole) {
        validator.verifyOtp(request);
        String phone = validator.phone(request);
        String prefix = validator.phonePrefix(request);
        OtpProvider.OtpResult result = provider.verify(prefix, phone, request.otp);
        if (result.success()) markVerified(proofRole, prefix, phone);
        return result;
    }

    public void requireRegistrationProof(String prefix, String phone) {
        if (!proofRequiredForRegister) return;
        Boolean verified = cache.get(proofKey("user", prefix, phone), Boolean.class);
        if (!Boolean.TRUE.equals(verified)) throw AuthException.unauthorized("OTP verification required");
        cache.invalidate(proofKey("user", prefix, phone)); // one-time receipt
    }

    private void markVerified(String role, String prefix, String phone) {
        cache.set(proofKey(role, prefix, phone), true, proofTtlSeconds);
    }

    private String proofKey(String role, String prefix, String phone) {
        return PROOF_PREFIX + role + ":" + prefix + ":" + phone;
    }

    private void requireActive(Document account) {
        if (account != null && Boolean.FALSE.equals(account.get("status"))) throw new AuthException("ACCOUNT_BLOCK");
    }

    private void requireConsultantActive(Document consultant) {
        if (!Boolean.TRUE.equals(consultant.get("isAdminVerify"))) throw new AuthException("CONSULTANT_NOT_VERIFY_BY_ADMIN");
        if (Boolean.FALSE.equals(consultant.get("status"))) throw new AuthException("ACCOUNT_BLOCK");
        if (Boolean.TRUE.equals(consultant.get("isDeleted"))) throw new AuthException("CONSULTANT_NOT_EXIST");
    }
}
