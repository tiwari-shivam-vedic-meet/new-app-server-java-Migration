package com.vedicmeet.appserver.admin;

import com.vedicmeet.appserver.migration.MigrationWrite;
import com.vedicmeet.appserver.security.AuthPrincipal;
import com.vedicmeet.appserver.security.AuthUserService;
import com.vedicmeet.appserver.security.CurrentUser;
import com.vedicmeet.appserver.security.RequireRole;
import com.vedicmeet.appserver.security.Role;
import org.bson.Document;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/v2/admin/support")
@RequireRole({Role.ADMIN, Role.SUB_ADMIN})
public class AdminSupportController {

    private final AdminSupportService service;
    private final AuthUserService users;

    public AdminSupportController(AdminSupportService service, AuthUserService users) {
        this.service = service;
        this.users = users;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(@RequestParam Map<String, String> query) {
        return AdminResponses.execute("Support list fetched successfully", () -> service.masters(query));
    }

    @PostMapping("/add")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> add(@RequestBody Map<String, Object> body) {
        return AdminResponses.execute("Support added successfully", () -> service.addMaster(body));
    }

    @PutMapping("/update")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> update(@RequestBody Map<String, Object> body) {
        return AdminResponses.execute("Support updated successfully", () -> service.updateMaster(body));
    }

    @GetMapping("/query")
    public ResponseEntity<Map<String, Object>> queries(@RequestParam Map<String, String> query) {
        return AdminResponses.execute("Support query list fetched successfully", () -> service.queries(query));
    }

    @GetMapping("/support-insights")
    public ResponseEntity<Map<String, Object>> insights() {
        return AdminResponses.execute("Support insights fetched successfully", service::insights);
    }

    @PutMapping("/update-is-admin-reply")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> replied(@RequestBody Map<String, Object> body) {
        return AdminResponses.execute("Support query list fetched successfully",
                () -> service.updateAdminReply(AdminResponses.text(body, "id")));
    }

    @PostMapping(value = "/chat_send", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> chatMultipart(@CurrentUser AuthPrincipal principal,
            @RequestParam Map<String, String> params,
            @RequestPart(value = "file", required = false) MultipartFile file) {
        return AdminResponses.execute("Support chat sent successfully",
                () -> service.sendChat(objectMap(params), admin(principal), file));
    }

    @PostMapping(value = "/chat_send", consumes = MediaType.APPLICATION_JSON_VALUE)
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> chatJson(@CurrentUser AuthPrincipal principal,
            @RequestBody Map<String, Object> body) {
        return AdminResponses.execute("Support chat sent successfully",
                () -> service.sendChat(body, admin(principal), null));
    }

    @GetMapping("/chat")
    public ResponseEntity<Map<String, Object>> chats(@RequestParam String customerSupportQueryId,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "10") int limit) {
        return AdminResponses.execute("Support chat list fetched successfully",
                () -> service.chats(customerSupportQueryId, page, limit));
    }

    @GetMapping("/query_details")
    public ResponseEntity<Map<String, Object>> details(@RequestParam String customerSupportQueryId) {
        return AdminResponses.execute("Query details fetched successfully",
                () -> service.queryDetails(customerSupportQueryId));
    }

    @PatchMapping("/query_status")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> queryStatus(@RequestBody Map<String, Object> body) {
        return AdminResponses.execute("Support query status updated successfully", () -> service.updateQueryStatus(
                AdminResponses.text(body, "customerSupportQueryId"), AdminResponses.optional(body, "status"), body.get("closedAt")));
    }

    @PostMapping("/refund/amount")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> refund(@RequestBody Map<String, Object> body) {
        return AdminResponses.execute("Support refund amount to user successfully", () -> service.refund(body));
    }

    @DeleteMapping("/delete")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> delete(@RequestBody Map<String, Object> body) {
        return AdminResponses.execute("Support query deleted successfully",
                () -> { service.deleteMaster(AdminResponses.text(body, "supportId")); return null; });
    }

    @PostMapping("/initiate_query")
    @MigrationWrite
    public ResponseEntity<Map<String, Object>> initiate(@RequestBody Map<String, Object> body) {
        return AdminResponses.execute("Support query initiated successfully", () -> service.initiateQuery(body));
    }

    @GetMapping("/get_chat_history")
    public ResponseEntity<Map<String, Object>> history(@RequestParam String threadId,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "10") int limit) {
        return AdminResponses.execute("Chat history fetched successfully",
                () -> service.chatHistory(threadId, page, limit));
    }

    private Document admin(AuthPrincipal principal) {
        Document admin = users.load(principal);
        if (admin == null) throw new IllegalStateException("ADMIN_NOT_EXIST");
        return admin;
    }

    private Map<String, Object> objectMap(Map<String, String> input) {
        return input == null ? new LinkedHashMap<>() : new LinkedHashMap<>(input);
    }
}
