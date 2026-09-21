package com.vedicmeet.appserver.content;

import com.vedicmeet.appserver.migration.MigrationWrite;
import com.vedicmeet.appserver.security.AuthPrincipal;
import com.vedicmeet.appserver.security.AuthUserService;
import com.vedicmeet.appserver.security.CurrentUser;
import com.vedicmeet.appserver.security.RequireRole;
import com.vedicmeet.appserver.security.Role;
import org.bson.Document;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
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

/** Exact mobile route/message surface from Node {@code rest-apis/modules/task-remedy.js}. */
@RestController
@RequestMapping("/v2/v1/task")
@RequireRole({Role.USER, Role.CONSULTANT})
public class TaskRemedyController {
    private final TaskRemedyService service;
    private final AuthUserService users;

    public TaskRemedyController(TaskRemedyService service, AuthUserService users) {
        this.service = service;
        this.users = users;
    }

    @PostMapping("/add") @MigrationWrite
    public Map<String, Object> add(@CurrentUser AuthPrincipal principal,
                                   @RequestBody Map<String, Object> body) {
        return call("Task added successfully", () -> service.addTask(body, text(body, "userType"), actor(principal)));
    }

    @PutMapping("/update") @MigrationWrite
    public Map<String, Object> update(@CurrentUser AuthPrincipal principal,
                                      @RequestBody Map<String, Object> body) {
        return call("Task updated successfully", () -> {
            service.editTask(body, text(body, "userType"), actor(principal)); return null;
        });
    }

    /** Node refreshes dates from this GET, therefore this compatibility route is write-gated. */
    @GetMapping("/details") @MigrationWrite
    public Map<String, Object> details(@RequestParam String taskRemedyId) {
        return call("Task details fetched successfully", () -> service.details(taskRemedyId));
    }

    @PutMapping("/check_uncheck") @MigrationWrite
    public ResponseEntity<Map<String, Object>> check(@CurrentUser AuthPrincipal principal,
                                                      @RequestBody Map<String, Object> body) {
        try {
            service.checkUncheck(text(body, "taskRemedyId"), text(body, "timeId"),
                    body.get("status") instanceof Number n ? n : null, actor(principal));
            return ResponseEntity.ok(success("Task checked/unchecked successfully", null));
        } catch (Exception error) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(failure(error));
        }
    }

    @DeleteMapping("/delete") @MigrationWrite
    public Map<String, Object> delete(@RequestBody Map<String, Object> body) {
        return call("Task deleted successfully", () -> service.deleteTask(text(body, "taskRemedyId")));
    }

    @GetMapping("/list")
    public Map<String, Object> list(@CurrentUser AuthPrincipal principal,
                                    @RequestParam(required = false) Integer page,
                                    @RequestParam(required = false) Integer limit,
                                    @RequestParam(required = false) String search) {
        return call("Task list fetched successfully", () -> service.listTasks(actor(principal), page, limit, search));
    }

    @PostMapping("/cons_add") @MigrationWrite
    public Map<String, Object> consultantAdd(@CurrentUser AuthPrincipal principal,
                                             @RequestBody Map<String, Object> body) {
        return call("Cons task added successfully", () -> service.addConsultantTask(body, actor(principal)));
    }

    @PutMapping("/cons_update") @MigrationWrite
    public Map<String, Object> consultantUpdate(@RequestBody Map<String, Object> body) {
        return call("Cons task updated successfully", () -> service.editConsultantTask(body));
    }

    @DeleteMapping("/cons_delete") @MigrationWrite
    public Map<String, Object> consultantDelete(@RequestBody Map<String, Object> body) {
        return call("Cons task deleted successfully", () -> service.deleteConsultantTask(text(body, "taskId")));
    }

    @GetMapping("/cons_details")
    public Map<String, Object> consultantDetails(@RequestParam String taskId,
                                                 @RequestParam(required = false) String date) {
        return call("Cons task details fetched successfully", () -> service.consultantTaskDetails(taskId, date));
    }

    /** Node marks expired tasks complete before listing, so this GET is write-gated. */
    @GetMapping("/cons_list") @MigrationWrite
    public Map<String, Object> consultantList(@CurrentUser AuthPrincipal principal,
                                              @RequestParam String type,
                                              @RequestParam(required = false) String fromDate,
                                              @RequestParam(required = false) String toDate,
                                              @RequestParam(required = false) Integer page,
                                              @RequestParam(required = false) Integer limit) {
        return call("Cons task list fetched successfully", () -> service.consultantTasks(
                actor(principal), type, fromDate, toDate, page, limit));
    }

    @PostMapping(value = "/add_order_task_remedy", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @MigrationWrite
    public Map<String, Object> addOrderRemedy(@CurrentUser AuthPrincipal principal,
                                              @RequestParam Map<String, String> fields,
                                              @RequestPart(value = "remedyAttachment", required = false)
                                              MultipartFile remedyAttachment) {
        return call("Order task remedy added successfully", () -> service.addOrderRemedy(
                new LinkedHashMap<>(fields), actor(principal), remedyAttachment));
    }

    @GetMapping("/order_task_remedy")
    public Map<String, Object> orderRemedies(@CurrentUser AuthPrincipal principal,
                                             @RequestParam String type,
                                             @RequestParam(required = false) String consultantRequestFormId,
                                             @RequestParam(required = false) Integer page,
                                             @RequestParam(required = false) Integer limit) {
        return call("Order task remedy list fetched successfully", () -> service.orderRemedies(
                actor(principal), actorType(principal), type, consultantRequestFormId, page, limit));
    }

    @GetMapping("/area_of_concern")
    public Map<String, Object> area(@RequestParam String type,
                                    @RequestParam(required = false) String areaOfConcernId,
                                    @RequestParam(required = false) String areaOfConcernRemedyId) {
        return call("Area of concern fetched successfully",
                () -> service.areaOfConcern(type, areaOfConcernId, areaOfConcernRemedyId));
    }

    private Document actor(AuthPrincipal principal) {
        Document actor = users.load(principal);
        if (actor == null) throw new IllegalStateException("ACCOUNT_NOT_FOUND");
        return actor;
    }

    private String actorType(AuthPrincipal principal) {
        return Role.CONSULTANT.equals(principal.getRole()) ? "cons" : "user";
    }

    private Map<String, Object> call(String message, Work work) {
        try { return success(message, work.run()); }
        catch (Exception error) { return failure(error); }
    }

    private Map<String, Object> success(String message, Object data) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", 200); out.put("success", true); out.put("message", message); out.put("data", data);
        return out;
    }

    private Map<String, Object> failure(Exception error) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", 500); out.put("success", false); out.put("message", error.getMessage());
        return out;
    }

    private String text(Map<String, ?> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value == null ? null : String.valueOf(value);
    }

    @FunctionalInterface private interface Work { Object run(); }
}
