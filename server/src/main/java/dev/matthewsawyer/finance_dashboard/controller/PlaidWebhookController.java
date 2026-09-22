package dev.matthewsawyer.finance_dashboard.controller;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.matthewsawyer.finance_dashboard.repository.PlaidItemRepository;
import dev.matthewsawyer.finance_dashboard.service.PlaidRecurringStreamSyncService;
import dev.matthewsawyer.finance_dashboard.service.PlaidTransactionSyncService;
import dev.matthewsawyer.finance_dashboard.service.PlaidWebhookVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Public endpoint: Plaid authenticates itself with a signature rather than a user token.
 */
@RestController
@RequestMapping("/plaid")
public class PlaidWebhookController {

    private static final Logger log = LoggerFactory.getLogger(PlaidWebhookController.class);

    private static final String TRANSACTIONS = "TRANSACTIONS";
    private static final String SYNC_UPDATES_AVAILABLE = "SYNC_UPDATES_AVAILABLE";
    private static final String RECURRING_TRANSACTIONS = "RECURRING_TRANSACTIONS";
    private static final String RECURRING_TRANSACTIONS_UPDATE = "RECURRING_TRANSACTIONS_UPDATE";

    private final PlaidWebhookVerifier webhookVerifier;
    private final PlaidItemRepository plaidItemRepository;
    private final PlaidTransactionSyncService transactionSyncService;
    private final PlaidRecurringStreamSyncService recurringStreamSyncService;
    private final ObjectMapper objectMapper;

    public PlaidWebhookController(
            PlaidWebhookVerifier webhookVerifier,
            PlaidItemRepository plaidItemRepository,
            PlaidTransactionSyncService transactionSyncService,
            PlaidRecurringStreamSyncService recurringStreamSyncService,
            ObjectMapper objectMapper
    ) {
        this.webhookVerifier = webhookVerifier;
        this.plaidItemRepository = plaidItemRepository;
        this.transactionSyncService = transactionSyncService;
        this.recurringStreamSyncService = recurringStreamSyncService;
        this.objectMapper = objectMapper;
    }

    /**
     * Takes the body as a string because the signature covers Plaid's exact bytes, which
     * a deserialized object could not reproduce.
     */
    @PostMapping("/webhook")
    @ResponseStatus(HttpStatus.OK)
    public void receiveWebhook(
            @RequestBody String rawBody,
            @RequestHeader(name = "Plaid-Verification", required = false) String verification
    ) {
        if (!webhookVerifier.isValid(rawBody, verification)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid Plaid webhook signature");
        }

        WebhookPayload payload = parse(rawBody);
        log.info(
                "Received Plaid webhook {}/{} for item {}",
                payload.webhookType(),
                payload.webhookCode(),
                payload.itemId());

        boolean syncTransactions = TRANSACTIONS.equals(payload.webhookType())
                && SYNC_UPDATES_AVAILABLE.equals(payload.webhookCode());
        boolean syncRecurring = RECURRING_TRANSACTIONS.equals(payload.webhookType())
                && RECURRING_TRANSACTIONS_UPDATE.equals(payload.webhookCode());

        if (!syncTransactions && !syncRecurring) {
            log.info(
                    "Ignoring Plaid webhook {}/{} for item {}",
                    payload.webhookType(),
                    payload.webhookCode(),
                    payload.itemId());
            return;
        }

        // Answer unknown items with a 200 so Plaid stops retrying a webhook we cannot act on.
        if (payload.itemId() == null || !plaidItemRepository.existsById(payload.itemId())) {
            log.warn("Ignoring webhook for unknown item {}", payload.itemId());
            return;
        }

        if (syncTransactions) {
            log.info("Queuing transactions sync for item {}", payload.itemId());
            transactionSyncService.syncItemAsync(payload.itemId());
        }
        if (syncRecurring || syncTransactions) {
            log.info("Queuing recurring stream sync for item {}", payload.itemId());
            recurringStreamSyncService.syncItemAsync(payload.itemId());
        }
    }

    private WebhookPayload parse(String rawBody) {
        try {
            return objectMapper.readValue(rawBody, WebhookPayload.class);
        } catch (JacksonException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unreadable Plaid webhook body");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WebhookPayload(
            @JsonProperty("webhook_type") String webhookType,
            @JsonProperty("webhook_code") String webhookCode,
            @JsonProperty("item_id") String itemId
    ) {
    }
}
