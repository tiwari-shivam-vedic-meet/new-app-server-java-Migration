package com.vedicmeet.appserver.auth.exception;

import org.springframework.http.HttpStatus;

/** Business/contract exception carrying the legacy HTTP and body-code mapping. */
public class AuthException extends RuntimeException {

    private final HttpStatus httpStatus;
    private final int bodyCode;

    public AuthException(String message) {
        this(message, HttpStatus.OK, 500);
    }

    public AuthException(String message, HttpStatus httpStatus, int bodyCode) {
        super(message);
        this.httpStatus = httpStatus;
        this.bodyCode = bodyCode;
    }

    public HttpStatus getHttpStatus() { return httpStatus; }
    public int getBodyCode() { return bodyCode; }

    public static AuthException badRequest(String message) {
        return new AuthException(message, HttpStatus.BAD_REQUEST, 400);
    }

    public static AuthException unauthorized(String message) {
        return new AuthException(message, HttpStatus.UNAUTHORIZED, 401);
    }
}
