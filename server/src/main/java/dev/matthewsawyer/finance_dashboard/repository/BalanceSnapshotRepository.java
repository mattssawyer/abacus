package dev.matthewsawyer.finance_dashboard.repository;

import dev.matthewsawyer.finance_dashboard.model.BalanceSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface BalanceSnapshotRepository extends JpaRepository<BalanceSnapshot, BalanceSnapshot.Key> {

    List<BalanceSnapshot> findAllByUserIdAndDateLessThanEqual(UUID userId, LocalDate to);
}
