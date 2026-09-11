package dev.matthewsawyer.finance_dashboard.controller;

import com.plaid.client.model.AccountsGetRequest;
import com.plaid.client.model.AccountsGetResponse;
import com.plaid.client.model.ItemPublicTokenExchangeRequest;
import com.plaid.client.model.ItemPublicTokenExchangeResponse;
import com.plaid.client.request.PlaidApi;
import dev.matthewsawyer.finance_dashboard.model.PlaidItem;
import dev.matthewsawyer.finance_dashboard.repository.PlaidItemRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import retrofit2.Call;
import retrofit2.Response;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlaidControllerTests {

    @Mock
    private PlaidApi plaidApi;

    @Mock
    private PlaidItemRepository plaidItemRepository;

    @Mock
    private Call<ItemPublicTokenExchangeResponse> exchangeCall;

    @Mock
    private Call<AccountsGetResponse> accountsCall;

    @Test
    void exchangesPublicTokenAndStoresAccessToken() throws IOException {
        ItemPublicTokenExchangeResponse plaidResponse = new ItemPublicTokenExchangeResponse()
                .itemId("item-id")
                .accessToken("access-token")
                .requestId("request-id");

        when(plaidApi.itemPublicTokenExchange(any(ItemPublicTokenExchangeRequest.class)))
                .thenReturn(exchangeCall);
        when(exchangeCall.execute()).thenReturn(Response.success(plaidResponse));

        PlaidController controller = new PlaidController(plaidApi, plaidItemRepository);
        Map<String, String> result = controller.exchangePublicToken(
                new PlaidController.ExchangePublicTokenRequest("public-token")
        );

        ArgumentCaptor<PlaidItem> itemCaptor = ArgumentCaptor.forClass(PlaidItem.class);
        verify(plaidItemRepository).save(itemCaptor.capture());

        assertEquals("item-id", result.get("item_id"));
        assertEquals("item-id", itemCaptor.getValue().getItemId());
        assertEquals("access-token", itemCaptor.getValue().getAccessToken());
    }

    @Test
    void rejectsBlankPublicToken() {
        PlaidController controller = new PlaidController(plaidApi, plaidItemRepository);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> controller.exchangePublicToken(
                        new PlaidController.ExchangePublicTokenRequest(" ")
                )
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verifyNoInteractions(plaidApi, plaidItemRepository);
    }

    @Test
    void getsAccountsUsingStoredAccessToken() throws IOException {
        PlaidItem plaidItem = new PlaidItem("item-id", "access-token");
        AccountsGetResponse plaidResponse = new AccountsGetResponse();

        when(plaidItemRepository.findById("item-id")).thenReturn(Optional.of(plaidItem));
        when(plaidApi.accountsGet(any(AccountsGetRequest.class))).thenReturn(accountsCall);
        when(accountsCall.execute()).thenReturn(Response.success(plaidResponse));

        PlaidController controller = new PlaidController(plaidApi, plaidItemRepository);
        AccountsGetResponse result = controller.getAccounts("item-id");

        ArgumentCaptor<AccountsGetRequest> requestCaptor =
                ArgumentCaptor.forClass(AccountsGetRequest.class);
        verify(plaidApi).accountsGet(requestCaptor.capture());

        assertSame(plaidResponse, result);
        assertEquals("access-token", requestCaptor.getValue().getAccessToken());
    }

    @Test
    void returnsNotFoundForUnknownItem() {
        when(plaidItemRepository.findById("missing-item")).thenReturn(Optional.empty());

        PlaidController controller = new PlaidController(plaidApi, plaidItemRepository);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> controller.getAccounts("missing-item")
        );

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
        verifyNoInteractions(plaidApi);
    }
}
