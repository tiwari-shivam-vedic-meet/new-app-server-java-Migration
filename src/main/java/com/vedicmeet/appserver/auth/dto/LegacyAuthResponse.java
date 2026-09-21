package com.vedicmeet.appserver.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Auth-specific envelopes: mobile routes use data; admin/consultant routes use result. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LegacyAuthResponse {

    private boolean success;
    private Integer code;
    private String message;
    private Object data;
    private Object result;
    private Boolean error;

    public LegacyAuthResponse() { }

    public static LegacyAuthResponse mobile(boolean success, Integer code, String message, Object data) {
        LegacyAuthResponse response = new LegacyAuthResponse();
        response.success = success;
        response.code = code;
        response.message = message;
        response.data = data;
        return response;
    }

    public static LegacyAuthResponse admin(boolean success, int code, String message,
                                           Object result, Boolean error) {
        LegacyAuthResponse response = new LegacyAuthResponse();
        response.success = success;
        response.code = code;
        response.message = message;
        response.result = result;
        response.error = error;
        return response;
    }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
    public Integer getCode() { return code; }
    public void setCode(Integer code) { this.code = code; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public Object getData() { return data; }
    public void setData(Object data) { this.data = data; }
    public Object getResult() { return result; }
    public void setResult(Object result) { this.result = result; }
    public Boolean getError() { return error; }
    public void setError(Boolean error) { this.error = error; }
}
