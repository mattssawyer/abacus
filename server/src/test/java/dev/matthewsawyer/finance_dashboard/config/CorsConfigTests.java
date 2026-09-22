package dev.matthewsawyer.finance_dashboard.config;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "PLAID_CLIENT_ID=test-client-id",
        "PLAID_SANDBOX_SECRET=test-secret"
})
@AutoConfigureMockMvc
class CorsConfigTests {

    private static final String FRONTEND = "http://localhost:5173";

    @Autowired
    private MockMvc mockMvc;

    /** Browsers preflight every cross-origin call the app makes; each method it uses must pass. */
    @ParameterizedTest
    @CsvSource({
            "GET, /plaid/accounts",
            "POST, /plaid/items",
            "GET, /spending-plan",
            "PUT, /spending-plan"
    })
    void allowsThePreflightForEveryMethodTheFrontendUses(String method, String path) throws Exception {
        mockMvc.perform(options(path)
                        .header("Origin", FRONTEND)
                        .header("Access-Control-Request-Method", method)
                        .header("Access-Control-Request-Headers", "authorization,content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", FRONTEND));
    }
}
