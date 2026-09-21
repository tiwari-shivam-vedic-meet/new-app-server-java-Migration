package com.vedicmeet.appserver.auth.service;

import com.vedicmeet.appserver.auth.cache.AuthDocumentCache;
import com.vedicmeet.appserver.auth.dto.AdminAuthRequests;
import com.vedicmeet.appserver.auth.exception.AuthException;
import com.vedicmeet.appserver.auth.provider.PasswordResetMailProvider;
import com.vedicmeet.appserver.auth.repository.AuthRepository;
import com.vedicmeet.appserver.auth.validation.AuthRequestValidator;
import com.vedicmeet.appserver.security.AuthPrincipal;
import com.vedicmeet.appserver.security.JwtService;
import com.vedicmeet.appserver.security.Role;
import com.vedicmeet.appserver.security.TokenRevocationService;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Admin/sub-admin authentication port with the legacy routes and hardened reset semantics. */
@Service
public class AdminAuthService {

    private final AuthRepository repository;
    private final AuthRequestValidator validator;
    private final PasswordEncoder passwords;
    private final AuthTokenFactory tokens;
    private final JwtService jwt;
    private final PasswordResetMailProvider mail;
    private final AuthDocumentCache cache;
    private final TokenRevocationService revocations;
    private final boolean bootstrapEnabled;
    private final long resetTtlSeconds;
    private final SecureRandom random = new SecureRandom();

    public AdminAuthService(AuthRepository repository, AuthRequestValidator validator,
                            PasswordEncoder passwords, AuthTokenFactory tokens, JwtService jwt,
                            PasswordResetMailProvider mail, AuthDocumentCache cache,
                            TokenRevocationService revocations,
                            @Value("${vedicmeet.auth.admin-bootstrap-enabled:false}") boolean bootstrapEnabled,
                            @Value("${vedicmeet.auth.password-reset-ttl-seconds:900}") long resetTtlSeconds) {
        this.repository = repository;
        this.validator = validator;
        this.passwords = passwords;
        this.tokens = tokens;
        this.jwt = jwt;
        this.mail = mail;
        this.cache = cache;
        this.revocations = revocations;
        this.bootstrapEnabled = bootstrapEnabled;
        this.resetTtlSeconds = resetTtlSeconds;
    }

    public Map<String, Object> login(AdminAuthRequests.LoginRequest request) {
        validator.adminLogin(request);
        String email = validator.email(request.email);
        Document admin = repository.findAdminByEmail(email);
        if (admin == null) throw loginFailure("Email not registered");
        if (Boolean.FALSE.equals(admin.get("status"))) throw loginFailure("Your account is blocked");
        if (!passwords.matches(request.password, String.valueOf(admin.get("password")))) {
            throw loginFailure("Invalid password");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("email", admin.get("email"));
        result.put("id", admin.get("_id"));
        result.put("rights", admin.get("rights") == null ? new Document() : admin.get("rights"));
        result.put("role", admin.get("role"));
        result.put("token", tokens.accountToken(String.valueOf(admin.get("role")), admin));
        return result;
    }

    @Transactional
    public SignupResult signUp(AdminAuthRequests.SignupRequest request, AuthPrincipal caller) {
        validator.adminSignup(request);
        authorizeSignup(caller, request.role);
        String email = validator.email(request.email);
        if (Role.ADMIN.equals(request.role) && repository.countAdminsByRole(Role.ADMIN) > 0) {
            throw new AuthException("ADMIN_SHOULD_BE_ONE");
        }
        if (repository.findAdminByEmail(email) != null) throw new AuthException("EMAIL_EXIST");

        String plainPassword = Role.SUB_ADMIN.equals(request.role)
                ? generatedSubAdminPassword() : request.password;
        Document admin = new Document("role", request.role).append("name", request.name)
                .append("email", email).append("password", passwords.encode(plainPassword))
                .append("mobile", "").append("isFreeTrailOffer", false)
                .append("rights", request.rights == null ? new Document() : new Document(request.rights))
                .append("status", true).append("authTokenVersion", 0);
        repository.insertAdmin(admin);
        return new SignupResult(sanitize(admin), Role.SUB_ADMIN.equals(request.role) ? plainPassword : "");
    }

    public Map<String, Object> list(int page, int limit, String search, Boolean status) {
        int safePage = Math.max(1, page);
        int safeLimit = Math.max(1, Math.min(limit, 100));
        List<Document> list = repository.listSubAdmins(safePage, safeLimit, search, status);
        List<Document> safe = new ArrayList<>();
        for (Document item : list) safe.add(sanitize(item));
        return Map.of("list", safe, "total", repository.countSubAdmins(search, status));
    }

    @Transactional
    public Document update(AdminAuthRequests.UpdateRequest request) {
        validator.adminUpdate(request);
        ObjectId id = new ObjectId(request.subAdminId);
        Document existing = requireSubAdmin(id);
        Document set = new Document();
        if (request.name != null) set.put("name", request.name);
        if (request.email != null) {
            String email = validator.email(request.email);
            Document duplicate = repository.findAdminByEmail(email);
            if (duplicate != null && !objectId(duplicate).equals(id)) throw new AuthException("EMAIL_EXIST");
            set.put("email", email);
        }
        if (request.rights != null) set.put("rights", new Document(request.rights));
        Document updated = repository.updateAdmin(id, set);
        cache.invalidate(Role.SUB_ADMIN, existing);
        return sanitize(updated);
    }

    @Transactional
    public Document status(AdminAuthRequests.StatusRequest request) {
        validator.adminStatus(request);
        ObjectId id = new ObjectId(request.subAdminId);
        Document existing = requireSubAdmin(id);
        Document updated = repository.setAdminStatus(id, Boolean.TRUE.equals(request.status));
        cache.invalidate(Role.SUB_ADMIN, existing);
        if (updated != null) cache.invalidate(Role.SUB_ADMIN, updated);
        return sanitize(updated);
    }

    public Document details(String id) {
        validator.adminId(id);
        return sanitize(requireSubAdmin(new ObjectId(id)));
    }

    @Transactional
    public void changePassword(AdminAuthRequests.ChangePasswordRequest request,
                               AuthPrincipal principal, Document account) {
        validator.changePassword(request);
        if (!passwords.matches(request.oldPassword, String.valueOf(account.get("password")))) {
            throw new AuthException("OLD_PASS_INCORRECT");
        }
        Document updated = repository.updateAdminPassword(objectId(account),
                passwords.encode(request.confirmPassword), true);
        cache.invalidate(principal.getRole(), account);
        if (updated != null) cache.invalidate(principal.getRole(), updated);
        revocations.revoke(principal);
    }

    @Transactional
    public void forgotPassword(AdminAuthRequests.ForgotPasswordRequest request) {
        String email = validator.forgotEmail(request);
        Document admin = repository.findAdminByEmail(email);
        if (admin == null) throw new AuthException("EMAIL_NOT_EXIST");
        String nonce = randomToken();
        repository.savePasswordReset(objectId(admin), sha256(nonce),
                Date.from(Instant.now().plusSeconds(resetTtlSeconds)));
        String token = tokens.passwordResetToken(admin, nonce);
        // Provider is disabled by default; no token is returned or logged.
        mail.send(email, string(admin.get("name")), token);
    }

    @Transactional
    public void resetPassword(AdminAuthRequests.ResetPasswordRequest request) {
        validator.resetPassword(request);
        Map<String, Object> claims;
        try { claims = jwt.verify(request.token); }
        catch (RuntimeException error) { throw new AuthException("Invalid or expired reset token"); }
        if (!"password_reset".equals(string(claims.get("purpose")))) {
            throw new AuthException("Invalid or expired reset token");
        }
        String id = string(claims.get("_id"));
        String nonce = string(claims.get("nonce"));
        if (!ObjectId.isValid(id) || nonce == null) throw new AuthException("Invalid or expired reset token");
        Document before = repository.findAdminById(id);
        String accountEmail = before == null ? null : string(before.get("email"));
        String tokenEmail = string(claims.get("email"));
        if (before == null || accountEmail == null || tokenEmail == null
                || !accountEmail.equalsIgnoreCase(tokenEmail)) {
            throw new AuthException("Invalid or expired reset token");
        }
        Document updated = repository.consumePasswordReset(new ObjectId(id), sha256(nonce),
                passwords.encode(request.confirmPassword));
        if (updated == null) throw new AuthException("Invalid or expired reset token");
        cache.invalidate(string(before.get("role")), before);
        cache.invalidate(string(updated.get("role")), updated);
    }

    private void authorizeSignup(AuthPrincipal caller, String requestedRole) {
        if (caller == null) {
            boolean firstAdmin = Role.ADMIN.equals(requestedRole)
                    && repository.countAdminsByRole(Role.ADMIN) == 0;
            if (!bootstrapEnabled || !firstAdmin) throw AuthException.unauthorized("Invalid token");
            return;
        }
        if (!Role.ADMIN.equals(caller.getRole()) || !ObjectId.isValid(caller.getId())) {
            throw AuthException.unauthorized("Invalid token");
        }
        if (revocations.isRevoked(caller)) throw AuthException.unauthorized("Invalid token");
        Document admin = repository.findAdminById(caller.getId());
        if (admin == null || Boolean.FALSE.equals(admin.get("status"))) {
            throw AuthException.unauthorized("Invalid token");
        }
        if (caller.getTokenVersion() != null) {
            Object value = admin.get("authTokenVersion");
            int current = value instanceof Number number ? number.intValue() : 0;
            if (current != caller.getTokenVersion()) throw AuthException.unauthorized("Invalid token");
        }
    }

    private Document requireSubAdmin(ObjectId id) {
        Document admin = repository.findAdminById(id);
        if (admin == null || !Role.SUB_ADMIN.equals(admin.getString("role"))) {
            throw new AuthException("SUB_ADMIN_NOT_EXIST");
        }
        return admin;
    }

    private Document sanitize(Document source) {
        if (source == null) return null;
        Document safe = new Document(source);
        safe.remove("password");
        safe.remove("passwordResetNonceHash");
        safe.remove("passwordResetExpiresAt");
        return safe;
    }

    private AuthException loginFailure(String message) {
        return new AuthException(message, HttpStatus.OK, 400);
    }

    private String generatedSubAdminPassword() {
        String millis = String.valueOf(System.currentTimeMillis());
        return "Vedic@" + millis.charAt(millis.length() - 1) + millis.charAt(millis.length() - 2);
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sha256(String value) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private ObjectId objectId(Document document) {
        Object value = document == null ? null : document.get("_id");
        if (value instanceof ObjectId oid) return oid;
        if (value != null && ObjectId.isValid(String.valueOf(value))) return new ObjectId(String.valueOf(value));
        throw new AuthException("SUB_ADMIN_NOT_EXIST");
    }

    private String string(Object value) { return value == null ? null : String.valueOf(value); }

    public record SignupResult(Document adminData, String subAdminPassword) { }
}
