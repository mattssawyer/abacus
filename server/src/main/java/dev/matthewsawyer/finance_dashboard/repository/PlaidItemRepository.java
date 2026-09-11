package dev.matthewsawyer.finance_dashboard.repository;

import dev.matthewsawyer.finance_dashboard.model.PlaidItem;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlaidItemRepository extends JpaRepository<PlaidItem, String> {
}
