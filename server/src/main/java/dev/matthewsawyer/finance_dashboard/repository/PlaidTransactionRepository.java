package dev.matthewsawyer.finance_dashboard.repository;

import dev.matthewsawyer.finance_dashboard.model.PlaidTransaction;
import dev.matthewsawyer.finance_dashboard.model.PlanPart;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PlaidTransactionRepository extends JpaRepository<PlaidTransaction, String> {

    @Query("""
            SELECT t FROM PlaidTransaction t
            WHERE t.userId = :userId
              AND (:accountId IS NULL OR t.accountId = :accountId)
            ORDER BY t.transactionDate DESC, t.transactionId ASC
            """)
    List<PlaidTransaction> findRecent(
            @Param("userId") UUID userId,
            @Param("accountId") String accountId,
            Pageable pageable);

    void deleteAllByItemIdAndTransactionIdIn(String itemId, Collection<String> transactionIds);

    /**
     * The spending in a date range, newest first: every transaction except pay, card payments and
     * money that only moved between the user's own accounts. Transactions not sorted yet are
     * included with no plan part.
     */
    @Query("""
            SELECT t FROM PlaidTransaction t
            WHERE t.userId = :userId
              AND t.transactionDate BETWEEN :start AND :end
              AND (:accountId IS NULL OR t.accountId = :accountId)
              AND (t.planPart IS NULL
                   OR t.planPart <> dev.matthewsawyer.finance_dashboard.model.PlanPart.NOT_COUNTED)
              AND (t.personalFinanceCategoryPrimary IS NULL
                   OR t.personalFinanceCategoryPrimary NOT IN :excludedCategories)
              AND (t.personalFinanceCategoryDetailed IS NULL
                   OR t.personalFinanceCategoryDetailed NOT IN :excludedCategories)
            ORDER BY t.transactionDate DESC, t.transactionId ASC
            """)
    List<PlaidTransaction> findSpending(
            @Param("userId") UUID userId,
            @Param("start") LocalDate start,
            @Param("end") LocalDate end,
            @Param("accountId") String accountId,
            @Param("excludedCategories") Collection<String> excludedCategories);

    /**
     * The user's transactions that need a plan part, newest first so this month's are sorted
     * before older ones. With {@code includeSorted}, already sorted transactions come back too.
     */
    @Query("""
            SELECT t FROM PlaidTransaction t
            WHERE t.userId = :userId
              AND (:includeSorted = TRUE OR t.planPart IS NULL)
              AND (t.personalFinanceCategoryPrimary IS NULL
                   OR t.personalFinanceCategoryPrimary NOT IN :excludedCategories)
              AND (t.personalFinanceCategoryDetailed IS NULL
                   OR t.personalFinanceCategoryDetailed NOT IN :excludedCategories)
            ORDER BY t.transactionDate DESC, t.transactionId ASC
            """)
    List<PlaidTransaction> findToSort(
            @Param("userId") UUID userId,
            @Param("includeSorted") boolean includeSorted,
            @Param("excludedCategories") Collection<String> excludedCategories);

    /**
     * Stores a transaction's plan part unless sync changed the transaction after it was read,
     * in which case the part may no longer fit and the next sort picks it up. Returns the rows
     * updated.
     */
    @Modifying
    @Transactional
    @Query("""
            UPDATE PlaidTransaction t SET t.planPart = :part
            WHERE t.transactionId = :transactionId AND t.updatedAt = :readUpdatedAt
            """)
    int updatePlanPart(
            @Param("transactionId") String transactionId,
            @Param("readUpdatedAt") Instant readUpdatedAt,
            @Param("part") PlanPart part);
}
