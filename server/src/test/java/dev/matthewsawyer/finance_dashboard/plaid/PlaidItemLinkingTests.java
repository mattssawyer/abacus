package dev.matthewsawyer.finance_dashboard.plaid;

import com.plaid.client.model.ItemPublicTokenExchangeRequest;
import com.plaid.client.model.ItemPublicTokenExchangeResponse;
import com.plaid.client.model.LinkTokenCreateRequest;
import com.plaid.client.model.LinkTokenCreateResponse;
import com.plaid.client.request.PlaidApi;
import dev.matthewsawyer.finance_dashboard.TestPlaidKeysets;
import dev.matthewsawyer.finance_dashboard.model.PlaidItem;
import dev.matthewsawyer.finance_dashboard.repository.PlaidItemRepository;
import okhttp3.MediaType;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import retrofit2.Call;
import retrofit2.Response;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlaidItemLinkingTests {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String WEBHOOK_URL = "https://abacus.test/api/plaid/webhook";

    @Mock
    private PlaidApi plaidApi;

    @Mock
    private PlaidItemRepository plaidItemRepository;

    @Mock
    private PlaidItemSync itemSync;

    @Mock
    private Call<LinkTokenCreateResponse> linkTokenCall;

    @Mock
    private Call<ItemPublicTokenExchangeResponse> exchangeCall;

    private final PlaidTokenEncryption tokenEncryption = new PlaidTokenEncryption(
            TestPlaidKeysets.create());
    private PlaidItemLinking linking;

    @BeforeEach
    void setUp() {
        linking = linking(WEBHOOK_URL);
    }

    @Test
    void pointsLinkTokensAtTheWebhookUrl() throws IOException {
        stubLinkToken();

        assertEquals("link-token", linking.createLinkToken(USER_ID));

        LinkTokenCreateRequest request = captureLinkTokenRequest();
        assertEquals(WEBHOOK_URL, request.getWebhook());
        assertEquals(USER_ID.toString(), request.getUser().getClientUserId());
    }

    @Test
    void omitsTheWebhookUrlWhenItIsNotConfigured() throws IOException {
        stubLinkToken();

        linking("").createLinkToken(USER_ID);

        assertNull(captureLinkTokenRequest().getWebhook());
    }

    @Test
    void storesTheEncryptedAccessTokenAndSyncsTheItem() throws IOException {
        stubExchange("access-token");
        when(plaidItemRepository.findByItemIdAndUserId("item-id", USER_ID)).thenReturn(Optional.empty());

        assertEquals("item-id", linking.link(USER_ID, "public-token"));

        ArgumentCaptor<ItemPublicTokenExchangeRequest> requestCaptor =
                ArgumentCaptor.forClass(ItemPublicTokenExchangeRequest.class);
        verify(plaidApi).itemPublicTokenExchange(requestCaptor.capture());
        assertEquals("public-token", requestCaptor.getValue().getPublicToken());

        PlaidItem saved = captureSavedItem();
        assertEquals("item-id", saved.getItemId());
        assertEquals(USER_ID, saved.getUserId());
        String encrypted = saved.getEncryptedAccessToken();
        assertNotEquals("access-token", encrypted);
        assertEquals("access-token", tokenEncryption.decrypt(encrypted, USER_ID, "item-id"));
        verify(itemSync).linked(saved);
    }

    @Test
    void keepsTheExistingCursorWhenAnItemIsRelinked() throws IOException {
        PlaidItem existing = new PlaidItem("item-id", "old-encrypted-token", USER_ID);
        ReflectionTestUtils.setField(existing, "transactionsCursor", "stored-cursor");
        stubExchange("new-access-token");
        when(plaidItemRepository.findByItemIdAndUserId("item-id", USER_ID)).thenReturn(Optional.of(existing));

        linking.link(USER_ID, "public-token");

        PlaidItem saved = captureSavedItem();
        assertEquals("stored-cursor", saved.getTransactionsCursor());
        assertEquals("new-access-token", tokenEncryption.decrypt(
                saved.getEncryptedAccessToken(), USER_ID, "item-id"));
    }

    @Test
    void storesNothingWhenPlaidRejectsTheExchange() throws IOException {
        when(plaidApi.itemPublicTokenExchange(any(ItemPublicTokenExchangeRequest.class)))
                .thenReturn(exchangeCall);
        when(exchangeCall.execute()).thenReturn(
                Response.error(500, ResponseBody.create("{}", MediaType.get("application/json"))));

        assertThrows(PlaidRequestException.class, () -> linking.link(USER_ID, "public-token"));

        verifyNoInteractions(plaidItemRepository, itemSync);
    }

    private PlaidItemLinking linking(String webhookUrl) {
        return new PlaidItemLinking(plaidApi, plaidItemRepository, tokenEncryption, itemSync, webhookUrl);
    }

    private void stubLinkToken() throws IOException {
        when(plaidApi.linkTokenCreate(any(LinkTokenCreateRequest.class))).thenReturn(linkTokenCall);
        when(linkTokenCall.execute()).thenReturn(
                Response.success(new LinkTokenCreateResponse().linkToken("link-token")));
    }

    private LinkTokenCreateRequest captureLinkTokenRequest() {
        ArgumentCaptor<LinkTokenCreateRequest> captor = ArgumentCaptor.forClass(LinkTokenCreateRequest.class);
        verify(plaidApi).linkTokenCreate(captor.capture());
        return captor.getValue();
    }

    private void stubExchange(String accessToken) throws IOException {
        when(plaidApi.itemPublicTokenExchange(any(ItemPublicTokenExchangeRequest.class)))
                .thenReturn(exchangeCall);
        when(exchangeCall.execute()).thenReturn(Response.success(new ItemPublicTokenExchangeResponse()
                .itemId("item-id")
                .accessToken(accessToken)
                .requestId("request-id")));
    }

    private PlaidItem captureSavedItem() {
        ArgumentCaptor<PlaidItem> captor = ArgumentCaptor.forClass(PlaidItem.class);
        verify(plaidItemRepository).save(captor.capture());
        return captor.getValue();
    }
}
