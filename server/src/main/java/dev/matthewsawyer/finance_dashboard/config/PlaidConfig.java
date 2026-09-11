package dev.matthewsawyer.finance_dashboard.config;

import com.plaid.client.ApiClient;
import com.plaid.client.request.PlaidApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;

@Configuration
public class PlaidConfig {

    @Bean
    public PlaidApi plaidApi(
            @Value("${PLAID_CLIENT_ID}") String clientId,
            @Value("${PLAID_SANDBOX_SECRET}") String secret
    ) {
        HashMap<String, String> keys = new HashMap<>();
        keys.put("clientId", clientId);
        keys.put("secret", secret);

        ApiClient apiClient = new ApiClient(keys);
        apiClient.setPlaidAdapter(ApiClient.Sandbox);
        return apiClient.createService(PlaidApi.class);
    }
}