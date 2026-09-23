package dev.matthewsawyer.finance_dashboard.model;

import dev.matthewsawyer.finance_dashboard.repository.PlaidItemRepository;
import dev.matthewsawyer.finance_dashboard.repository.UserRepository;
import dev.matthewsawyer.finance_dashboard.plaid.PlaidTokenEncryption;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "PLAID_CLIENT_ID=test-client-id",
        "PLAID_SANDBOX_SECRET=test-secret"
})
@Transactional
class PlaidItemEncryptionTests {
    @Autowired private PlaidItemRepository items;
    @Autowired private UserRepository users;
    @Autowired private PlaidTokenEncryption encryption;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;

    @Test
    void storesCiphertextInDatabaseAndDecryptsAfterReload() {
        User user = users.saveAndFlush(new User("encryption-test-user"));
        String ciphertext = encryption.encrypt("access-sandbox-test", user.getId(), "test-item");
        items.saveAndFlush(new PlaidItem("test-item", ciphertext, user.getId()));
        entityManager.clear();
        String raw = jdbc.queryForObject(
                "SELECT access_token_encrypted FROM plaid_items WHERE item_id = ?", String.class, "test-item");
        assertEquals(ciphertext, raw);
        assertNotEquals("access-sandbox-test", raw);
        PlaidItem reloaded = items.findByItemIdAndUserId("test-item", user.getId()).orElseThrow();
        assertEquals("access-sandbox-test", encryption.decrypt(
                reloaded.getEncryptedAccessToken(), user.getId(), reloaded.getItemId()));
    }
}
