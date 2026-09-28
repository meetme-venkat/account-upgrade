package com.mercur.upgrade.web;

import com.mercur.upgrade.messaging.PublishException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;
import java.util.stream.Stream;

/**
 * Maps exceptions to RFC 9457 problem responses. Ordered ahead of Spring Boot's generic
 * problem-details handler so validation errors carry the field-level {@code errors} list.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Bean validation failure on a single {@code @Valid @RequestBody}. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleInvalidBody(MethodArgumentNotValidException e) {
        List<String> errors = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .toList();
        return validationProblem(errors);
    }

    /** Bean validation failure on method parameters, e.g. elements of the batch list. */
    @ExceptionHandler(HandlerMethodValidationException.class)
    ProblemDetail handleInvalidParameters(HandlerMethodValidationException e) {
        List<String> errors = e.getParameterValidationResults().stream()
                .flatMap(GlobalExceptionHandler::describe)
                .toList();
        return validationProblem(errors);
    }

    /** Batch element errors are prefixed with their index, e.g. {@code [2].userId: userId is required}. */
    private static Stream<String> describe(ParameterValidationResult result) {
        String prefix = result.getContainerIndex() == null ? "" : "[" + result.getContainerIndex() + "].";
        if (result instanceof ParameterErrors parameterErrors) {
            return parameterErrors.getFieldErrors().stream()
                    .map(error -> prefix + error.getField() + ": " + error.getDefaultMessage());
        }
        return result.getResolvableErrors().stream().map(MessageSourceResolvable::getDefaultMessage);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail handleUnreadable(HttpMessageNotReadableException e) {
        if (e.getMostSpecificCause() instanceof RequestBodySizeLimitFilter.PayloadTooLargeException tooLarge) {
            return problem(HttpStatus.CONTENT_TOO_LARGE, "Payload too large", tooLarge.getMessage());
        }
        return problem(HttpStatus.BAD_REQUEST, "Malformed request", "Request body is missing or is not valid JSON");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid parameter",
                "Invalid value '%s' for parameter '%s'".formatted(e.getValue(), e.getName()));
    }

    @ExceptionHandler(PublishException.class)
    ProblemDetail handlePublishFailure(PublishException e) {
        log.warn("Publishing failed: {}", e.getMessage());
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Event broker unavailable",
                "The request could not be queued, please retry later");
    }

    private static ProblemDetail validationProblem(List<String> errors) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Validation failed", "Request validation failed");
        problem.setProperty("errors", errors);
        return problem;
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
