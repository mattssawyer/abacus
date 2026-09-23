package dev.matthewsawyer.finance_dashboard.controller;

import dev.matthewsawyer.finance_dashboard.plaid.PlaidRequestException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlaidExceptionHandlerTests {

    @Test
    void answersPlaidFailuresWithBadGateway() {
        ProblemDetail problem = new PlaidExceptionHandler().handlePlaidRequestFailure(
                new PlaidRequestException("Plaid token exchange failed"));

        assertEquals(HttpStatus.BAD_GATEWAY.value(), problem.getStatus());
        assertEquals("Plaid token exchange failed", problem.getDetail());
    }
}
