package io.github.rmkrdev.gateway.web;

import io.github.rmkrdev.gateway.limits.LimitExceededException;
import io.github.rmkrdev.gateway.pii.PiiBlockedException;
import io.github.rmkrdev.gateway.report.InvalidReportException;
import io.github.rmkrdev.gateway.routing.AllTargetsFailedException;
import io.github.rmkrdev.gateway.routing.RequestRejectedException;
import io.github.rmkrdev.gateway.routing.UnknownModelException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns routing outcomes into RFC 9457 problem responses. Attempts list provider ids, model
 * names and status codes only, never URLs or keys.
 */
@RestControllerAdvice
class GatewayExceptionHandler {

    @ExceptionHandler
    ProblemDetail unknownModel(UnknownModelException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setTitle("Unknown model");
        return problem;
    }

    @ExceptionHandler
    ProblemDetail allTargetsFailed(AllTargetsFailedException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, e.getMessage());
        problem.setTitle("No provider answered");
        problem.setProperty("attempts", e.attempts());
        return problem;
    }

    @ExceptionHandler
    ProblemDetail requestRejected(RequestRejectedException e) {
        HttpStatus status = HttpStatus.resolve(e.status());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                status == null ? HttpStatus.BAD_REQUEST : status, e.getMessage());
        problem.setTitle("Provider rejected the request");
        problem.setProperty("attempts", e.attempts());
        return problem;
    }

    /**
     * Over budget or over rate: {@code 429} with {@code Retry-After}, and a {@code code} of
     * {@code budget_exceeded} or {@code rate_limited} so clients can tell them apart.
     */
    @ExceptionHandler
    ResponseEntity<ProblemDetail> limitExceeded(LimitExceededException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
        boolean budget = LimitExceededException.BUDGET_EXCEEDED.equals(e.code());
        problem.setTitle(budget ? "Budget used up" : "Rate limit reached");
        problem.setProperty("code", e.code());
        problem.setProperty("limit", e.limit());
        problem.setProperty("client", e.client());
        if (e.resetsAt() != null) {
            problem.setProperty("resets_at", e.resetsAt().toString());
        }
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSeconds()))
                .body(problem);
    }

    /**
     * A prompt with personal data under the {@code block} mode: {@code 422} with
     * {@code code: pii_detected} and counts by detector, never the values.
     */
    @ExceptionHandler
    ProblemDetail piiBlocked(PiiBlockedException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
        problem.setTitle("Personal data not allowed");
        problem.setProperty("code", PiiBlockedException.CODE);
        problem.setProperty("detected", e.counts());
        return problem;
    }

    @ExceptionHandler
    ProblemDetail invalidReport(InvalidReportException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Invalid report request");
        return problem;
    }
}
