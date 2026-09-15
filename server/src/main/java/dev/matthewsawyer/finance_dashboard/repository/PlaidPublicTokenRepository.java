package dev.matthewsawyer.finance_dashboard.repository;

import dev.matthewsawyer.finance_dashboard.model.PlaidPublicToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PlaidPublicTokenRepository extends JpaRepository<PlaidPublicToken, UUID> {
}
