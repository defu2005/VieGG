package com.rentgg.shared.error;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.rentgg.shared.web.TraceIdFilter;
import jakarta.persistence.OptimisticLockException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException ex) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(ex.status());
        if (ex.status() == HttpStatus.TOO_MANY_REQUESTS && ex.details().get("retryAfterSeconds") != null) {
            builder.header("Retry-After", String.valueOf(ex.details().get("retryAfterSeconds")));
        }
        return builder.body(body(ex.code(), ex.getMessage(), ex.details()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        List<Map<String, Object>> fields = new ArrayList<>();
        ex.getBindingResult().getFieldErrors().forEach(f -> fields.add(fieldError(f.getField(), f.getDefaultMessage())));
        ex.getBindingResult().getGlobalErrors().forEach(g -> fields.add(fieldError(g.getObjectName(), g.getDefaultMessage())));
        return validationError(fields);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponse> handleMethodValidation(HandlerMethodValidationException ex) {
        List<Map<String, Object>> fields = ex.getAllValidationResults().stream()
                .flatMap(r -> r.getResolvableErrors().stream()
                        .map(e -> fieldError(r.getMethodParameter().getParameterName(), e.getDefaultMessage())))
                .toList();
        return validationError(fields);
    }

    /** Lists every field error in the message itself, so clients that only show `message` still say what to fix. */
    private static ResponseEntity<ErrorResponse> validationError(List<Map<String, Object>> fields) {
        String summary = fields.stream()
                .map(f -> f.get("field") + ": " + String.valueOf(f.get("message")).replaceAll("\\.$", ""))
                .collect(Collectors.joining("; "));
        String message = fields.isEmpty() ? "Dữ liệu không hợp lệ." : "Dữ liệu không hợp lệ — " + summary + ".";
        return ResponseEntity.badRequest().body(body("VALIDATION_ERROR", message, Map.of("fields", fields)));
    }

    private static Map<String, Object> fieldError(String field, String message) {
        return Map.of("field", field == null ? "param" : field, "message", message == null ? "không hợp lệ" : message);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException ex) {
        if ("Idempotency-Key".equalsIgnoreCase(ex.getHeaderName())) {
            return ResponseEntity.badRequest()
                    .body(body("IDEMPOTENCY_KEY_REQUIRED", "Thiếu header Idempotency-Key.", Map.of()));
        }
        return ResponseEntity.badRequest().body(body("MALFORMED_REQUEST", "Thiếu header " + ex.getHeaderName(), Map.of()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleMalformed(HttpMessageNotReadableException ex) {
        // A body that parses but has a wrong value type (text in a number, unknown enum) names the offending field.
        if (ex.getCause() instanceof JsonMappingException jme && !jme.getPath().isEmpty()) {
            String field = jme.getPath().stream()
                    .map(r -> r.getFieldName() != null ? r.getFieldName() : "[" + r.getIndex() + "]")
                    .collect(Collectors.joining(".")).replace(".[", "[");
            return ResponseEntity.badRequest().body(body("MALFORMED_REQUEST",
                    "Giá trị của trường " + field + " không đúng kiểu dữ liệu.", Map.of("field", field)));
        }
        return ResponseEntity.badRequest().body(body("MALFORMED_REQUEST", "Nội dung yêu cầu không đúng định dạng JSON.", Map.of()));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(MissingServletRequestParameterException ex) {
        return ResponseEntity.badRequest()
                .body(body("MALFORMED_REQUEST", "Thiếu tham số " + ex.getParameterName() + ".", Map.of("param", ex.getParameterName())));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.badRequest()
                .body(body("MALFORMED_REQUEST", "Tham số " + ex.getName() + " không hợp lệ.", Map.of("param", ex.getName())));
    }

    @ExceptionHandler({ObjectOptimisticLockingFailureException.class, OptimisticLockException.class,
            PessimisticLockingFailureException.class, CannotAcquireLockException.class})
    public ResponseEntity<ErrorResponse> handleConcurrent(Exception ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(body("CONCURRENT_MODIFICATION", "Dữ liệu vừa bị thay đổi, vui lòng tải lại và thử lại.", Map.of()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleIntegrity(DataIntegrityViolationException ex) {
        ApiException mapped = DbErrors.map(ex);
        if (mapped != null) {
            return handleApi(mapped);
        }
        log.error("Unmapped data integrity violation", ex);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(body("CONFLICT", "Xung đột dữ liệu.", Map.of()));
    }

    @ExceptionHandler({AccessDeniedException.class, AuthorizationDeniedException.class})
    public ResponseEntity<ErrorResponse> handleAccessDenied(Exception ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(body("FORBIDDEN", "Bạn không có quyền thực hiện thao tác này.", Map.of()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body("NOT_FOUND", "Không tìm thấy.", Map.of()));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethod(HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(body("METHOD_NOT_ALLOWED", "Phương thức không được hỗ trợ.", Map.of()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleUploadSize(MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(body("FILE_TOO_LARGE", "Tệp quá lớn (tối đa 20 MB).", Map.of()));
    }

    @ExceptionHandler({MissingServletRequestPartException.class, MultipartException.class,
            HttpMediaTypeNotSupportedException.class})
    public ResponseEntity<ErrorResponse> handleMultipart(Exception ex) {
        return ResponseEntity.badRequest()
                .body(body("MALFORMED_REQUEST", "Cần gửi tệp dạng multipart/form-data, trường \"file\".", Map.of()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleOther(Exception ex) {
        SQLException sql = DbErrors.findSqlException(ex);
        if (sql != null) {
            ApiException mapped = DbErrors.mapSqlState(sql);
            if (mapped != null) {
                return handleApi(mapped);
            }
        }
        log.error("Unhandled error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(body("INTERNAL_ERROR", "Đã có lỗi xảy ra.", Map.of()));
    }

    private static ErrorResponse body(String code, String message, Map<String, Object> details) {
        return new ErrorResponse(code, message, details == null ? Map.of() : new LinkedHashMap<>(details),
                MDC.get(TraceIdFilter.MDC_KEY));
    }
}
