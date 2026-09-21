package com.vedicmeet.appserver.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;


@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Object>> handleValidation(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldError() != null
                ? ex.getBindingResult().getFieldError().getDefaultMessage()
                : "Validation failed";
        // Node returns 200 with success:false for validation problems.
        return ResponseEntity.ok(ApiResponse.fail(msg));
    }

    @ExceptionHandler(TokenException.class)
    public ResponseEntity<ApiResponse<Object>> handleToken(TokenException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.fail(401, ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Object>> handleGeneric(Exception ex) {
        // Node's catch blocks generally return 200 { success:false, message }.
        return ResponseEntity.ok(ApiResponse.fail(ex.getMessage() == null ? "Internal error" : ex.getMessage()));
    }


    public static class TokenException extends RuntimeException {
        public TokenException(String message) { super(message); }
    }
}
