package dev.matthewsawyer.finance_dashboard;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
		"PLAID_CLIENT_ID=test-client-id",
		"PLAID_SANDBOX_SECRET=test-secret"
})
class FinanceDashboardApplicationTests {

	@Test
	void contextLoads() {
	}

}
