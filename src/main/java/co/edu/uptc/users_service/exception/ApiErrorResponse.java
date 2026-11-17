package co.edu.uptc.users_service.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiErrorResponse(
        int status,
        String error,
        String message,
        String requestId,
        Map<String, Object> details) {

    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    public static ApiErrorResponse of(HttpStatus status, String message, HttpServletRequest request) {
        String requestId = request == null ? null : request.getHeader("X-Request-Id");
        if (requestId != null && !SAFE_REQUEST_ID.matcher(requestId).matches()) requestId = null;
        return new ApiErrorResponse(status.value(), codeFor(status), truncate(message), requestId, null);
    }

    public static ApiErrorResponse validation(HttpServletRequest request, Map<String, Object> details) {
        String requestId = request == null ? null : request.getHeader("X-Request-Id");
        if (requestId != null && !SAFE_REQUEST_ID.matcher(requestId).matches()) requestId = null;
        return new ApiErrorResponse(422, "VALIDATION_ERROR", "La solicitud contiene campos inválidos", requestId, details);
    }

    public static void write(
            HttpServletResponse response,
            ObjectMapper objectMapper,
            HttpStatus status,
            String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType("application/json");
        objectMapper.writeValue(response.getOutputStream(),
                new ApiErrorResponse(status.value(), codeFor(status), truncate(message), null, null));
    }

    private static String codeFor(HttpStatus status) {
        return switch (status) {
            case BAD_REQUEST -> "BAD_REQUEST";
            case UNAUTHORIZED -> "UNAUTHENTICATED";
            case FORBIDDEN -> "FORBIDDEN";
            case NOT_FOUND -> "NOT_FOUND";
            case CONFLICT -> "CONFLICT";
            case UNPROCESSABLE_ENTITY -> "VALIDATION_ERROR";
            case BAD_GATEWAY -> "UPSTREAM_ERROR";
            case SERVICE_UNAVAILABLE -> "UPSTREAM_UNAVAILABLE";
            default -> status.is5xxServerError() ? "INTERNAL_ERROR" : "REQUEST_ERROR";
        };
    }

    private static String truncate(String message) {
        String value = message == null || message.isBlank() ? "Error de solicitud" : message.trim();
        return value.substring(0, Math.min(value.length(), 300));
    }
}
