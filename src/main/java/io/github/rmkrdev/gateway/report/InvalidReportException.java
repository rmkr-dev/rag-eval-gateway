package io.github.rmkrdev.gateway.report;

/** A report request that cannot be answered as asked. Always maps to 400. */
public class InvalidReportException extends RuntimeException {

    public InvalidReportException(String message) {
        super(message);
    }
}
