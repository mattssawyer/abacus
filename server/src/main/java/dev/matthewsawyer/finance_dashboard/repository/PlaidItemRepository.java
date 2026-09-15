package dev.matthewsawyer.finance_dashboard.repository;

import dev.matthewsawyer.finance_dashboard.model.PlaidItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PlaidItemRepository extends JpaRepository<PlaidItem, String> {

    Optional<PlaidItem> findByItemIdAndUserId(String itemId, UUID userId);
}
