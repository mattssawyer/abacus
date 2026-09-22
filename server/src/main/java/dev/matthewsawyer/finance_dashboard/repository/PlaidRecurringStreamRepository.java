package dev.matthewsawyer.finance_dashboard.repository;

import dev.matthewsawyer.finance_dashboard.model.PlaidRecurringStream;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PlaidRecurringStreamRepository extends JpaRepository<PlaidRecurringStream, String> {

    List<PlaidRecurringStream> findAllByUserId(UUID userId);

    List<PlaidRecurringStream> findAllByUserIdAndAccountId(UUID userId, String accountId);

    void deleteAllByItemId(String itemId);
}
