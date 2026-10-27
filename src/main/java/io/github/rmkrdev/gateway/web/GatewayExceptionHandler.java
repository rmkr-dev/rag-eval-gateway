package io.github.rmkrdev.gateway.web;

import io.github.rmkrdev.gateway.report.InvalidReportException;
import io.github.rmkrdev.gateway.routing.AllTargetsFailedException;
import io.github.rmkrdev.gateway.routing.RequestRejectedException;
import io.github.rmkrdev.gateway.routing.UnknownModelException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
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

    @ExceptionHandler
    ProblemDetail invalidReport(InvalidReportException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Invalid report request");
        return problem;
    }
}
