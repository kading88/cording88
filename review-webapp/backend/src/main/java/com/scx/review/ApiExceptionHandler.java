package com.scx.review;

import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ApiError.class)
    ResponseEntity<?> api(ApiError e) {
        return ResponseEntity.status(e.status).body(Map.of("code", e.code, "message", e.getMessage()));
    }
    @ExceptionHandler({MethodArgumentNotValidException.class, MethodArgumentTypeMismatchException.class,
        HttpMessageNotReadableException.class, IllegalArgumentException.class})
    ResponseEntity<?> validation(Exception e) {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_INPUT", "message", "Check the submitted label, note, or search filters."));
    }
}
