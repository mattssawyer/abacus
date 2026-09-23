package dev.matthewsawyer.finance_dashboard.controller;

import dev.matthewsawyer.finance_dashboard.plaid.PlaidRequestException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Plaid failing is an upstream problem, not ours, so requests that depend on it get a 502. */
@RestControllerAdvice
public class PlaidExceptionHandler {

    @ExceptionHandler(PlaidRequestException.class)
    ProblemDetail handlePlaidRequestFailure(PlaidRequestException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, exception.getMessage());
    }
}
