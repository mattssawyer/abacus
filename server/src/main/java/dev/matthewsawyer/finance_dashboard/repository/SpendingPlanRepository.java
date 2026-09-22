package dev.matthewsawyer.finance_dashboard.repository;

import dev.matthewsawyer.finance_dashboard.model.SpendingPlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SpendingPlanRepository extends JpaRepository<SpendingPlan, UUID> {

    Optional<SpendingPlan> findByUserId(UUID userId);
}
