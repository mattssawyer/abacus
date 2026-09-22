package dev.matthewsawyer.finance_dashboard.controller;

import dev.matthewsawyer.finance_dashboard.repository.PlaidItemRepository;
import dev.matthewsawyer.finance_dashboard.service.PlaidRecurringStreamSyncService;
import dev.matthewsawyer.finance_dashboard.service.PlaidTransactionSyncService;
import dev.matthewsawyer.finance_dashboard.service.PlaidWebhookVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlaidWebhookControllerTests {

    private static final String SIGNATURE = "plaid-verification-jwt";
    private static final String SYNC_BODY = """
            {"webhook_type":"TRANSACTIONS","webhook_code":"SYNC_UPDATES_AVAILABLE",\
            "item_id":"item-id","environment":"sandbox"}""";
    private static final String RECURRING_BODY = """
            {"webhook_type":"RECURRING_TRANSACTIONS","webhook_code":"RECURRING_TRANSACTIONS_UPDATE",\
            "item_id":"item-id","environment":"sandbox"}""";

    @Mock
    private PlaidWebhookVerifier webhookVerifier;

    @Mock
    private PlaidItemRepository plaidItemRepository;

    @Mock
    private PlaidTransactionSyncService transactionSyncService;

    @Mock
    private PlaidRecurringStreamSyncService recurringStreamSyncService;

    private PlaidWebhookController controller;

    @BeforeEach
    void setUp() {
        controller = new PlaidWebhookController(
                webhookVerifier,
                plaidItemRepository,
                transactionSyncService,
                recurringStreamSyncService,
                new ObjectMapper()
        );
    }

    @Test
    void syncsTheItemWhenPlaidReportsUpdates() {
        when(webhookVerifier.isValid(SYNC_BODY, SIGNATURE)).thenReturn(true);
        when(plaidItemRepository.existsById("item-id")).thenReturn(true);

        controller.receiveWebhook(SYNC_BODY, SIGNATURE);

        verify(transactionSyncService).syncItemAsync("item-id");
        verify(recurringStreamSyncService).syncItemAsync("item-id");
    }

    @Test
    void syncsRecurringStreamsWhenPlaidReportsAnUpdate() {
        when(webhookVerifier.isValid(RECURRING_BODY, SIGNATURE)).thenReturn(true);
        when(plaidItemRepository.existsById("item-id")).thenReturn(true);

        controller.receiveWebhook(RECURRING_BODY, SIGNATURE);

        verify(recurringStreamSyncService).syncItemAsync("item-id");
        verifyNoInteractions(transactionSyncService);
    }

    @Test
    void rejectsWebhookWithAnInvalidSignature() {
        when(webhookVerifier.isValid(SYNC_BODY, SIGNATURE)).thenReturn(false);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class, () -> controller.receiveWebhook(SYNC_BODY, SIGNATURE));

        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatusCode());
        verifyNoInteractions(plaidItemRepository, transactionSyncService, recurringStreamSyncService);
    }

    @Test
    void acceptsButIgnoresWebhookForAnUnknownItem() {
        when(webhookVerifier.isValid(SYNC_BODY, SIGNATURE)).thenReturn(true);
        when(plaidItemRepository.existsById("item-id")).thenReturn(false);

        controller.receiveWebhook(SYNC_BODY, SIGNATURE);

        verifyNoInteractions(transactionSyncService, recurringStreamSyncService);
    }

    @Test
    void ignoresWebhookCodesOtherThanSyncUpdatesAvailable() {
        String body = """
                {"webhook_type":"ITEM","webhook_code":"ERROR","item_id":"item-id"}""";
        when(webhookVerifier.isValid(body, SIGNATURE)).thenReturn(true);

        controller.receiveWebhook(body, SIGNATURE);

        verifyNoInteractions(plaidItemRepository, transactionSyncService, recurringStreamSyncService);
    }

    @Test
    void ignoresTransactionsWebhooksFromTheLegacyGetIntegration() {
        String body = """
                {"webhook_type":"TRANSACTIONS","webhook_code":"DEFAULT_UPDATE","item_id":"item-id"}""";
        when(webhookVerifier.isValid(body, SIGNATURE)).thenReturn(true);

        controller.receiveWebhook(body, SIGNATURE);

        verifyNoInteractions(plaidItemRepository, transactionSyncService, recurringStreamSyncService);
    }

    @Test
    void rejectsAnUnreadableBody() {
        when(webhookVerifier.isValid("not-json", SIGNATURE)).thenReturn(true);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class, () -> controller.receiveWebhook("not-json", SIGNATURE));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verifyNoInteractions(plaidItemRepository, transactionSyncService, recurringStreamSyncService);
    }
}
