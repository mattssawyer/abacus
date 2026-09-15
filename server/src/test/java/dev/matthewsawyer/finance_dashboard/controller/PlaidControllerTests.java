package dev.matthewsawyer.finance_dashboard.controller;

import com.plaid.client.model.AccountsGetRequest;
import com.plaid.client.model.AccountsGetResponse;
import com.plaid.client.model.ItemPublicTokenExchangeRequest;
import com.plaid.client.model.ItemPublicTokenExchangeResponse;
import com.plaid.client.request.PlaidApi;
import dev.matthewsawyer.finance_dashboard.model.PlaidItem;
import dev.matthewsawyer.finance_dashboard.model.PlaidPublicToken;
import dev.matthewsawyer.finance_dashboard.model.User;
import dev.matthewsawyer.finance_dashboard.repository.PlaidItemRepository;
import dev.matthewsawyer.finance_dashboard.repository.PlaidPublicTokenRepository;
import dev.matthewsawyer.finance_dashboard.service.UserService;
import dev.matthewsawyer.finance_dashboard.service.PlaidTokenEncryption;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import dev.matthewsawyer.finance_dashboard.TestPlaidKeysets;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import retrofit2.Call;
import retrofit2.Response;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlaidControllerTests {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock
    private PlaidApi plaidApi;

    @Mock
    private PlaidItemRepository plaidItemRepository;

    @Mock
    private PlaidPublicTokenRepository plaidPublicTokenRepository;

    @Mock
    private UserService userService;

    @Mock
    private Call<ItemPublicTokenExchangeResponse> exchangeCall;

    @Mock
    private Call<AccountsGetResponse> accountsCall;

    private final PlaidTokenEncryption tokenEncryption = new PlaidTokenEncryption(
            TestPlaidKeysets.create());
    private PlaidController controller;
    private Jwt jwt;
    private User user;

    @BeforeEach
    void setUp() {
        controller = new PlaidController(
                plaidApi,
                plaidItemRepository,
                plaidPublicTokenRepository,
                userService,
                tokenEncryption
        );
        jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("clerk-user")
                .build();
        user = new User("clerk-user");
        ReflectionTestUtils.setField(user, "id", USER_ID);
    }

    @Test
    void savesPublicTokenForCurrentUser() {
        when(userService.getOrCreateUser(jwt)).thenReturn(user);

        controller.savePublicToken(
                jwt,
                new PlaidController.SavePublicTokenRequest("public-token")
        );

        ArgumentCaptor<PlaidPublicToken> tokenCaptor = ArgumentCaptor.forClass(PlaidPublicToken.class);
        verify(plaidPublicTokenRepository).save(tokenCaptor.capture());

        assertEquals(USER_ID, tokenCaptor.getValue().getUserId());
        assertEquals("public-token", tokenCaptor.getValue().getPublicToken());
    }

    @Test
    void rejectsBlankPublicToken() {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> controller.savePublicToken(
                        jwt,
                        new PlaidController.SavePublicTokenRequest(" ")
                )
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verifyNoInteractions(userService, plaidPublicTokenRepository, plaidApi, plaidItemRepository);
    }

    @Test
    void exchangesSavedPublicTokenAndStoresAccessToken() throws IOException {
        PlaidPublicToken storedToken = new PlaidPublicToken(USER_ID, "saved-public-token");
        ItemPublicTokenExchangeResponse plaidResponse = new ItemPublicTokenExchangeResponse()
                .itemId("item-id")
                .accessToken("access-token")
                .requestId("request-id");

        when(userService.getOrCreateUser(jwt)).thenReturn(user);
        when(plaidPublicTokenRepository.findById(USER_ID)).thenReturn(Optional.of(storedToken));
        when(plaidApi.itemPublicTokenExchange(any(ItemPublicTokenExchangeRequest.class)))
                .thenReturn(exchangeCall);
        when(exchangeCall.execute()).thenReturn(Response.success(plaidResponse));

        Map<String, String> result = controller.exchangePublicToken(jwt);

        ArgumentCaptor<ItemPublicTokenExchangeRequest> requestCaptor =
                ArgumentCaptor.forClass(ItemPublicTokenExchangeRequest.class);
        ArgumentCaptor<PlaidItem> itemCaptor = ArgumentCaptor.forClass(PlaidItem.class);
        verify(plaidApi).itemPublicTokenExchange(requestCaptor.capture());
        verify(plaidItemRepository).save(itemCaptor.capture());
        verify(plaidPublicTokenRepository).delete(storedToken);

        assertEquals("saved-public-token", requestCaptor.getValue().getPublicToken());
        assertEquals("item-id", result.get("item_id"));
        assertEquals("item-id", itemCaptor.getValue().getItemId());
        String encrypted = itemCaptor.getValue().getEncryptedAccessToken();
        assertNotEquals("access-token", encrypted);
        assertEquals("access-token", tokenEncryption.decrypt(encrypted, USER_ID, "item-id"));
        assertEquals(Map.of("item_id", "item-id"), result);
        assertEquals(USER_ID, itemCaptor.getValue().getUserId());
    }

    @Test
    void returnsNotFoundWhenNoPublicTokenIsSaved() {
        when(userService.getOrCreateUser(jwt)).thenReturn(user);
        when(plaidPublicTokenRepository.findById(USER_ID)).thenReturn(Optional.empty());

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> controller.exchangePublicToken(jwt)
        );

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
        verifyNoInteractions(plaidApi, plaidItemRepository);
    }

    @Test
    void getsAccountsUsingStoredAccessToken() throws IOException {
        PlaidItem plaidItem = new PlaidItem("item-id",
                tokenEncryption.encrypt("access-token", USER_ID, "item-id"), USER_ID);
        AccountsGetResponse plaidResponse = new AccountsGetResponse();

        when(userService.getOrCreateUser(jwt)).thenReturn(user);
        when(plaidItemRepository.findByItemIdAndUserId("item-id", USER_ID)).thenReturn(Optional.of(plaidItem));
        when(plaidApi.accountsGet(any(AccountsGetRequest.class))).thenReturn(accountsCall);
        when(accountsCall.execute()).thenReturn(Response.success(plaidResponse));

        AccountsGetResponse result = controller.getAccounts(jwt, "item-id");

        ArgumentCaptor<AccountsGetRequest> requestCaptor =
                ArgumentCaptor.forClass(AccountsGetRequest.class);
        verify(plaidApi).accountsGet(requestCaptor.capture());

        assertSame(plaidResponse, result);
        assertEquals("access-token", requestCaptor.getValue().getAccessToken());
    }

    @Test
    void refusesPlaintextStoredTokenBeforeCallingPlaid() {
        when(userService.getOrCreateUser(jwt)).thenReturn(user);
        when(plaidItemRepository.findByItemIdAndUserId("item-id", USER_ID))
                .thenReturn(Optional.of(new PlaidItem("item-id", "access-token", USER_ID)));

        assertThrows(IllegalStateException.class, () -> controller.getAccounts(jwt, "item-id"));
        verifyNoInteractions(plaidApi);
    }

    @Test
    void returnsNotFoundForUnknownItem() {
        when(userService.getOrCreateUser(jwt)).thenReturn(user);
        when(plaidItemRepository.findByItemIdAndUserId("missing-item", USER_ID)).thenReturn(Optional.empty());

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> controller.getAccounts(jwt, "missing-item")
        );

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
        verifyNoInteractions(plaidApi);
    }
}
