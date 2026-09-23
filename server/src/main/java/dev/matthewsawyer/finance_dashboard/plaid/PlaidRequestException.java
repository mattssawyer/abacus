package dev.matthewsawyer.finance_dashboard.plaid;

/** Plaid could not be reached or rejected a request. */
public class PlaidRequestException extends RuntimeException {

    public PlaidRequestException(String message) {
        super(message);
    }

    public PlaidRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
