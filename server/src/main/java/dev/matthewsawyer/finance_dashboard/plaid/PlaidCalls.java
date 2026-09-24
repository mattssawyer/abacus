package dev.matthewsawyer.finance_dashboard.plaid;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.ResponseBody;
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
            log.warn("Plaid {} failed with HTTP {}: {}", action, response.code(), plaidError(response));
            throw new PlaidRequestException("Plaid " + action + " failed");
        }
        return response.body();
    }

    /** Plaid's error type, code and message; never tokens or secrets. */
    private static String plaidError(Response<?> response) {
        try (ResponseBody body = response.errorBody()) {
            if (body == null) {
                return "no error body";
            }
            JsonObject error = JsonParser.parseString(body.string()).getAsJsonObject();
            return String.join(" / ", field(error, "error_type"), field(error, "error_code"),
                    field(error, "error_message"));
        } catch (IOException | RuntimeException e) {
            return "unreadable error body";
        }
    }

    private static String field(JsonObject error, String name) {
        return error.has(name) && !error.get(name).isJsonNull() ? error.get(name).getAsString() : "-";
    }

    private PlaidCalls() {
    }
}
