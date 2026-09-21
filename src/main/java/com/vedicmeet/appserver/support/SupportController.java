package com.vedicmeet.appserver.support;

import com.vedicmeet.appserver.crypto.CryptoService;
import com.vedicmeet.appserver.security.AuthPrincipal;
import com.vedicmeet.appserver.security.AuthUserService;
import com.vedicmeet.appserver.security.CurrentUser;
import com.vedicmeet.appserver.security.RequireRole;
import com.vedicmeet.appserver.security.Role;
import com.vedicmeet.appserver.web.ApiResponse;
import com.vedicmeet.appserver.migration.MigrationWrite;
import org.bson.Document;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Port of Node modules/support.js GET list (authed) and GET /i18n (PUBLIC).
 * The query/chat/customer routes are writes or user-specific reads out of this slice.
 */
@RestController
@RequestMapping("/v2/v1/support")
public class SupportController {

    private final SupportService service;
    private final AuthUserService authUserService;
    private final CryptoService cryptoService;

    public SupportController(SupportService service, AuthUserService authUserService, CryptoService cryptoService) {
        this.service = service;
        this.authUserService = authUserService;
        this.cryptoService = cryptoService;
    }

    @GetMapping
    @RequireRole({Role.USER, Role.CONSULTANT})
    public ApiResponse<?> list(@RequestParam(required = false) String supportType,
                               @RequestParam(required = false) Integer page,
                               @RequestParam(required = false) Integer limit,
                               @RequestParam(required = false) String search) {
        try {
            return ApiResponse.ok("Support master list fetched successfully",
                    service.listSupportMaster(supportType, page, limit, search));
        } catch (Exception error) {
            return new ApiResponse<>(false, 500, error.getMessage(), Collections.emptyMap());
        }
    }

    /** PUBLIC in Node (no auth middleware). */
    @GetMapping("/i18n")
    public ResponseEntity<Object> i18n(@RequestParam(required = false) String type,
                                       @RequestParam(required = false) String language) {
        try {
            Map<String, Object> result = service.i18n(type, language);
            if (result == null) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("code", 400);
                body.put("success", false);
                body.put("message", "Invalid type. Use languages, version, or language");
                body.put("result", new LinkedHashMap<>());
                return ResponseEntity.status(400).body(body);
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", 200);
            body.put("success", true);
            body.put("message", "Translations fetched successfully");
            body.put("result", result); // { result: {languages:...} } or { result: {language:...} }
            return ResponseEntity.ok(body);
        } catch (Exception error) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", 500);
            body.put("success", false);
            body.put("message", error.getMessage());
            body.put("result", new LinkedHashMap<>());
            return ResponseEntity.status(500).body(body);
        }
    }

    // ---- Prompt C writes (diff-pending). userType comes from the JWT role (user|consultant). ----

    /** POST /initiate_query — encrypted reqData body. */
    @PostMapping("/initiate_query")
    @MigrationWrite
    @RequireRole({Role.USER, Role.CONSULTANT})
    public ApiResponse<?> initiateQuery(@CurrentUser AuthPrincipal principal,
                                        @RequestBody(required = false) Map<String, Object> body) {
        try {
            Document user = authUserService.load(principal);
            Map<String, Object> input = decode(body);
            return ApiResponse.ok("Query initiated successfully",
                    service.customerQueryInitiate(input, user, principal.getRole()));
        } catch (Exception error) {
            return new ApiResponse<>(false, 500, error.getMessage(), Collections.emptyMap());
        }
    }

    /**
     * POST /chat_send — Node multer route: encrypted `reqData` field + optional `file`. userType is
     * always user|consultant here (the admin chat_send is a separate admin route), so isByAdmin=false.
     */
    @PostMapping(value = "/chat_send", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @MigrationWrite
    @RequireRole({Role.USER, Role.CONSULTANT})
    public ApiResponse<?> chatSend(@CurrentUser AuthPrincipal principal,
                                   @RequestParam(required = false) String reqData,
                                   @RequestPart(required = false) MultipartFile file) {
        try {
            Document user = authUserService.load(principal);
            Map<String, Object> input = (reqData != null && !reqData.isEmpty())
                    ? cryptoService.decryptToMap(reqData) : new LinkedHashMap<>();
            return ApiResponse.ok("Chat sent successfully",
                    service.supportChat(input, user, principal.getRole(), file));
        } catch (Exception error) {
            return new ApiResponse<>(false, 500, error.getMessage(), Collections.emptyMap());
        }
    }

    private Map<String, Object> decode(Map<String, Object> body) {
        if (body != null && body.get("reqData") instanceof String) {
            return cryptoService.decryptToMap((String) body.get("reqData"));
        }
        return body == null ? new LinkedHashMap<>() : body;
    }
}
