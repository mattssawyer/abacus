package dev.matthewsawyer.finance_dashboard.plaid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import retrofit2.Call;
import retrofit2.Response;

import java.io.IOException;

final class PlaidCalls {

    private static final Logger log = LoggerFactory.getLogger(PlaidCalls.class);

    /**
     * Runs a Plaid request and returns its body. {@code action} names the request in errors,
     * e.g. "transactions sync".
     */
    static <T> T execute(Call<T> call, String action) {
        Response<T> response;
        try {
            response = call.execute();
        } catch (IOException e) {
            throw new PlaidRequestException("Plaid " + action + " failed", e);
        }
        if (!response.isSuccessful() || response.body() == null) {
            log.warn("Plaid {} failed with HTTP {}", action, response.code());
            throw new PlaidRequestException("Plaid " + action + " failed");
        }
        return response.body();
    }

    private PlaidCalls() {
    }
}
