package com.scx.review;

import org.springframework.http.HttpStatus;

public class ApiError extends RuntimeException {
    public final HttpStatus status;
    public final String code;
    public ApiError(HttpStatus status, String code, String message) {
        super(message); this.status = status; this.code = code;
    }
}
