package com.vedicmeet.appserver.admin;

import org.bson.Document;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Shared renderer for the legacy admin contract: logical failures still use HTTP 200. */
final class AdminResponses {

    private AdminResponses() {}

    static ResponseEntity<Map<String, Object>> execute(String successMessage, Supplier<?> action) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", 200);
            body.put("success", true);
            body.put("message", successMessage);
            body.put("result", action.get());
            return ResponseEntity.ok(body);
        } catch (Exception error) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", 500);
            body.put("success", false);
            body.put("message", message(error));
            body.put("result", new Document());
            return ResponseEntity.ok(body);
        }
    }

    /** Node shape {success:true, message, data} (e.g. flag /get-flags). Failure -> {success:false, message}. */
    static ResponseEntity<Map<String, Object>> dataMessage(String successMessage, Supplier<?> action) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("message", successMessage);
            body.put("data", action.get());
            return ResponseEntity.ok(body);
        } catch (Exception error) {
            return ResponseEntity.ok(Map.of("success", false, "message", message(error)));
        }
    }

    /** Node shape {success:true, message} with NO data (e.g. DELETE deactivate routes). Failure -> {success:false, message}. */
    static ResponseEntity<Map<String, Object>> messageOnly(String successMessage, Supplier<?> action) {
        try {
            action.get(); // execute the write; result intentionally discarded to match Node's {success, message} shape
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("message", successMessage);
            return ResponseEntity.ok(body);
        } catch (Exception error) {
            return ResponseEntity.ok(Map.of("success", false, "message", message(error)));
        }
    }

    /** Node shape {code:200, success:true, message, data} (e.g. session-breakdown). Failure -> {code:500, success:false, message}. */
    static ResponseEntity<Map<String, Object>> executeData(String successMessage, Supplier<?> action) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", 200);
            body.put("success", true);
            body.put("message", successMessage);
            body.put("data", action.get());
            return ResponseEntity.ok(body);
        } catch (Exception error) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", 500);
            body.put("success", false);
            body.put("message", message(error));
            return ResponseEntity.ok(body);
        }
    }

    /** Node shape {success:true, code:200, message, data} (e.g. analytics.js). Failure -> {success:false, code:500, message, data:{}}. */
    static ResponseEntity<Map<String, Object>> executeCodeData(String successMessage, Supplier<?> action) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("code", 200);
            body.put("message", successMessage);
            body.put("data", action.get());
            return ResponseEntity.ok(body);
        } catch (Exception error) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("code", 500);
            body.put("message", message(error));
            body.put("data", new Document());
            return ResponseEntity.ok(body);
        }
    }

    static ResponseEntity<Map<String, Object>> simple(Supplier<?> action) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("data", action.get());
            return ResponseEntity.ok(body);
        } catch (Exception error) {
            return ResponseEntity.ok(Map.of("success", false, "message", message(error)));
        }
    }

    static String text(Map<String, Object> input, String key) {
        Object value = input == null ? null : input.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException(key.toUpperCase() + "_REQUIRE");
        }
        return String.valueOf(value);
    }

    static String optional(Map<String, Object> input, String key) {
        Object value = input == null ? null : input.get(key);
        return value == null ? null : String.valueOf(value);
    }

    static boolean bool(Object value) {
        if (value instanceof Boolean bool) return bool;
        if (value != null && ("true".equalsIgnoreCase(String.valueOf(value))
                || "false".equalsIgnoreCase(String.valueOf(value)))) {
            return Boolean.parseBoolean(String.valueOf(value));
        }
        throw new IllegalArgumentException("INVALID_STATUS");
    }

    static int integer(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try { return value == null ? fallback : Integer.parseInt(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private static String message(Exception error) {
        return error.getMessage() == null ? "Internal server error" : error.getMessage();
    }
}
