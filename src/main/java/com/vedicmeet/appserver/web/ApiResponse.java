package com.vedicmeet.appserver.web;

/**
 * The Node service's universal response envelope. Crucially, VedicMeet returns
 * HTTP 200 even for logical failures, with success=false in the body — the mobile
 * apps switch on `success`, not on the HTTP status. Controllers must preserve this
 * contract: return ApiResponse.fail(...) with a 200, not a 4xx.
 *
 * Shape (matches res.json({ success, code, message, result })):
 *   { "success": true|false, "code": 200, "message": "...", "result": <any> }
 */
public class ApiResponse<T> {

    private boolean success;
    private int code;
    private String message;
    private T result;

    public ApiResponse() {}

    public ApiResponse(boolean success, int code, String message, T result) {
        this.success = success;
        this.code = code;
        this.message = message;
        this.result = result;
    }

    public static <T> ApiResponse<T> ok(String message, T result) {
        return new ApiResponse<>(true, 200, message, result);
    }

    public static <T> ApiResponse<T> ok(T result) {
        return new ApiResponse<>(true, 200, "Success", result);
    }

    public static <T> ApiResponse<T> fail(String message) {
        return new ApiResponse<>(false, 200, message, null);
    }

    public static <T> ApiResponse<T> fail(int code, String message) {
        return new ApiResponse<>(false, code, message, null);
    }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public int getCode() { return code; }
    public void setCode(int code) { this.code = code; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public T getResult() { return result; }
    public void setResult(T result) { this.result = result; }
}
