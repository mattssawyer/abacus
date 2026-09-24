package dev.matthewsawyer.finance_dashboard.repository;

import dev.matthewsawyer.finance_dashboard.model.AccountDrop;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AccountDropRepository extends JpaRepository<AccountDrop, UUID> {

    List<AccountDrop> findAllByUserId(UUID userId);
}
