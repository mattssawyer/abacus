package dev.matthewsawyer.finance_dashboard.repository;

import dev.matthewsawyer.finance_dashboard.model.PlaidTransaction;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PlaidTransactionRepository extends JpaRepository<PlaidTransaction, String> {

    List<PlaidTransaction> findAllByUserIdOrderByTransactionDateDescTransactionIdAsc(
            UUID userId, Pageable pageable);

    void deleteAllByItemIdAndTransactionIdIn(String itemId, Collection<String> transactionIds);

    /**
     * Totals outflow per Plaid primary category. Plaid always assigns a category, but the column
     * is nullable, so anything missing one is reported as UNCATEGORIZED rather than dropped.
     */
    @Query("""
            SELECT COALESCE(t.personalFinanceCategoryPrimary, 'UNCATEGORIZED') AS category,
                   SUM(t.amount) AS total
            FROM PlaidTransaction t
            WHERE t.userId = :userId
              AND t.transactionDate BETWEEN :start AND :end
              AND (t.personalFinanceCategoryPrimary IS NULL
                   OR t.personalFinanceCategoryPrimary NOT IN :excludedCategories)
            GROUP BY COALESCE(t.personalFinanceCategoryPrimary, 'UNCATEGORIZED')
            """)
    List<CategoryTotal> sumSpendingByCategory(
            @Param("userId") UUID userId,
            @Param("start") LocalDate start,
            @Param("end") LocalDate end,
            @Param("excludedCategories") Collection<String> excludedCategories);

    interface CategoryTotal {

        String getCategory();

        BigDecimal getTotal();
    }
}
