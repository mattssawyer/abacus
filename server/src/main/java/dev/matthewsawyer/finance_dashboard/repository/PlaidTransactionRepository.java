package dev.matthewsawyer.finance_dashboard.repository;

import dev.matthewsawyer.finance_dashboard.model.PlaidTransaction;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PlaidTransactionRepository extends JpaRepository<PlaidTransaction, String> {

    List<PlaidTransaction> findAllByUserIdOrderByTransactionDateDescTransactionIdAsc(
            UUID userId, Pageable pageable);

    void deleteAllByItemIdAndTransactionIdIn(String itemId, Collection<String> transactionIds);
}
