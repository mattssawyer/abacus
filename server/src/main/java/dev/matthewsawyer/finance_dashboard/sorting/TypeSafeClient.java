package dev.matthewsawyer.finance_dashboard.sorting;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * Asks TypeSafe's System One model a single Choice question. See
 * <a href="https://docs.typesafe.ai/api">the API reference</a>.
 */
@Component
class TypeSafeClient {

    private static final String MODEL = "jev-latest";
    private static final String QUESTION_ID = "answer";

    // Rate limited and overloaded; both clear up after a short wait.
    private static final Set<Integer> RETRYABLE_STATUSES = Set.of(429, 529);
    private static final int MAX_ATTEMPTS = 3;
    private static final Duration FIRST_BACKOFF = Duration.ofSeconds(1);

    private final RestClient restClient;
    private final boolean configured;

    TypeSafeClient(
            @Value("${typesafe.api-key:}") String apiKey,
            @Value("${typesafe.base-url:https://api.typesafe.ai}") String baseUrl
    ) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(30));
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .build();
        this.configured = !apiKey.isBlank();
    }

    /** False when no API key is set, as in local setups that haven't added one yet. */
    boolean isConfigured() {
        return configured;
    }

    /**
     * Returns the chosen option, one of {@code question}'s criteria keys. Retries rate limiting
     * and overload with backoff; any other failure is thrown.
     */
    ChoiceAnswer choose(Object state, Map<String, Object> question) {
        Map<String, Object> body = Map.of(
                "model", MODEL,
                "state", state,
                "questions", Map.of(QUESTION_ID, question));

        Duration backoff = FIRST_BACKOFF;
        for (int attempt = 1; ; attempt++) {
            try {
                SystemOneResponse response = restClient.post()
                        .uri("/v1/systemone")
                        .body(body)
                        .retrieve()
                        .body(SystemOneResponse.class);
                if (response == null || response.answers() == null
                        || response.answers().get(QUESTION_ID) == null) {
                    throw new IllegalStateException("TypeSafe returned no answer");
                }
                return response.answers().get(QUESTION_ID);
            } catch (RestClientResponseException e) {
                if (attempt >= MAX_ATTEMPTS || !RETRYABLE_STATUSES.contains(e.getStatusCode().value())) {
                    throw e;
                }
            }
            sleep(backoff);
            backoff = backoff.multipliedBy(2);
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to retry TypeSafe", e);
        }
    }

    record ChoiceAnswer(
            @JsonProperty("choice") String choice,
            @JsonProperty("confidence") double confidence
    ) {
    }

    private record SystemOneResponse(@JsonProperty("answers") Map<String, ChoiceAnswer> answers) {
    }
}
