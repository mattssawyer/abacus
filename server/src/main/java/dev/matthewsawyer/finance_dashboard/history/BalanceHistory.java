package dev.matthewsawyer.finance_dashboard.history;

import dev.matthewsawyer.finance_dashboard.model.BalanceSnapshot;
import dev.matthewsawyer.finance_dashboard.model.PlaidAccount;
import dev.matthewsawyer.finance_dashboard.repository.BalanceSnapshotRepository;
import dev.matthewsawyer.finance_dashboard.repository.PlaidAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Records each account's balance snapshots and turns them into the investment and net worth
 * graphs.
 *
 * <p>Plaid only reports today's balances, so these snapshots are the only record of past ones
 * (see docs/adr/0001). A day with no snapshot carries the account's previous balance forward.
 * An account Plaid stops returning counts toward net worth only before the day it was dropped,
 * and loses its own series.
 */
@Service
public class BalanceHistory {

    private static final Set<String> ASSETS = Set.of("depository", "investment", "brokerage");
    private static final Set<String> LIABILITIES = Set.of("credit", "loan");
    // "brokerage" is Plaid's legacy name for the investment type.
    private static final Set<String> INVESTMENTS = Set.of("investment", "brokerage");
    private static final String NET_WORTH_CURRENCY = "USD";

    private final PlaidAccountRepository accountRepository;
    private final BalanceSnapshotRepository snapshotRepository;

    BalanceHistory(PlaidAccountRepository accountRepository, BalanceSnapshotRepository snapshotRepository) {
        this.accountRepository = accountRepository;
        this.snapshotRepository = snapshotRepository;
    }

    /**
     * Stores the item's current account balances as that day's snapshots, replacing any taken
     * earlier the same day. Dropped accounts and accounts without a current balance are skipped.
     */
    @Transactional
    public void record(String itemId, LocalDate date) {
        for (PlaidAccount account : accountRepository.findAllByItemId(itemId)) {
            if (account.isDropped() || account.getCurrentBalance() == null) {
                continue;
            }
            BalanceSnapshot snapshot = snapshotRepository
                    .findById(new BalanceSnapshot.Key(account.getAccountId(), date))
                    .orElseGet(() -> new BalanceSnapshot(account.getAccountId(), date, account.getUserId()));
            snapshot.updateBalance(account.getCurrentBalance());
            snapshotRepository.save(snapshot);
        }
    }

    /**
     * The user's daily balances from {@code from} (or their first snapshot, when null or earlier)
     * through {@code to}.
     */
    @Transactional(readOnly = true)
    public History forUser(UUID userId, LocalDate from, LocalDate to) {
        List<PlaidAccount> accounts = accountRepository.findAllByUserIdOrderByNameAscAccountIdAsc(userId);
        Map<String, NavigableMap<LocalDate, BigDecimal>> balances = new HashMap<>();
        for (BalanceSnapshot snapshot : snapshotRepository.findAllByUserIdAndDateLessThanEqual(userId, to)) {
            balances.computeIfAbsent(snapshot.getAccountId(), id -> new TreeMap<>())
                    .put(snapshot.getDate(), snapshot.getCurrentBalance());
        }

        List<AccountSeries> investmentAccounts = new ArrayList<>();
        List<PlaidAccount> counted = new ArrayList<>();
        List<String> leftOut = new ArrayList<>();
        for (PlaidAccount account : accounts) {
            NavigableMap<LocalDate, BigDecimal> accountBalances = balances.getOrDefault(account.getAccountId(), new TreeMap<>());
            if (INVESTMENTS.contains(account.getType()) && !account.isDropped()) {
                investmentAccounts.add(new AccountSeries(account.getAccountId(), daily(accountBalances, from, to)));
            }
            if (sign(account) == 0) {
                if (!account.isDropped()) {
                    leftOut.add(account.getAccountId());
                }
            } else {
                counted.add(account);
            }
        }

        LocalDate netWorthStart = counted.stream()
                .map(account -> balances.get(account.getAccountId()))
                .filter(accountBalances -> accountBalances != null)
                .map(NavigableMap::firstKey)
                .min(Comparator.naturalOrder())
                .orElse(null);
        if (netWorthStart == null) {
            return new History(List.of(), investmentAccounts, List.of(), List.of(), leftOut);
        }
        LocalDate start = from == null || from.isBefore(netWorthStart) ? netWorthStart : from;

        List<Point> netWorth = new ArrayList<>();
        for (LocalDate day = start; !day.isAfter(to); day = day.plusDays(1)) {
            BigDecimal total = BigDecimal.ZERO;
            for (PlaidAccount account : counted) {
                if (account.isDropped() && !day.isBefore(account.getDroppedOn())) {
                    continue;
                }
                BigDecimal balance = balanceOn(balances.get(account.getAccountId()), day);
                if (balance != null) {
                    total = total.add(balance.multiply(BigDecimal.valueOf(sign(account))));
                }
            }
            netWorth.add(new Point(day, total));
        }

        List<AccountAdded> accountsAdded = new ArrayList<>();
        for (PlaidAccount account : counted) {
            NavigableMap<LocalDate, BigDecimal> accountBalances = balances.get(account.getAccountId());
            if (accountBalances == null) {
                continue;
            }
            LocalDate first = accountBalances.firstKey();
            if (first.isAfter(netWorthStart) && !first.isBefore(start)) {
                accountsAdded.add(new AccountAdded(first, account.getAccountId(), account.getName()));
            }
        }
        accountsAdded.sort(Comparator.comparing(AccountAdded::day));

        List<AccountDropped> accountsDropped = new ArrayList<>();
        for (PlaidAccount account : counted) {
            LocalDate dropped = account.getDroppedOn();
            if (dropped != null && balances.containsKey(account.getAccountId())
                    && !dropped.isBefore(start) && !dropped.isAfter(to)) {
                accountsDropped.add(new AccountDropped(dropped, account.getAccountId(), account.getName()));
            }
        }
        accountsDropped.sort(Comparator.comparing(AccountDropped::day));

        return new History(netWorth, investmentAccounts, accountsAdded, accountsDropped, leftOut);
    }

    /** One point per day from the account's first snapshot (or {@code from}) through {@code to}. */
    private static List<Point> daily(NavigableMap<LocalDate, BigDecimal> balances, LocalDate from, LocalDate to) {
        if (balances.isEmpty()) {
            return List.of();
        }
        LocalDate first = balances.firstKey();
        LocalDate start = from == null || from.isBefore(first) ? first : from;
        List<Point> points = new ArrayList<>();
        for (LocalDate day = start; !day.isAfter(to); day = day.plusDays(1)) {
            points.add(new Point(day, balanceOn(balances, day)));
        }
        return points;
    }

    private static BigDecimal balanceOn(NavigableMap<LocalDate, BigDecimal> balances, LocalDate day) {
        if (balances == null) {
            return null;
        }
        Entry<LocalDate, BigDecimal> latest = balances.floorEntry(day);
        return latest == null ? null : latest.getValue();
    }

    /** +1 for what the user owns, -1 for what they owe, 0 for accounts net worth leaves out. */
    private static int sign(PlaidAccount account) {
        if (!NET_WORTH_CURRENCY.equals(account.getIsoCurrencyCode())) {
            return 0;
        }
        if (ASSETS.contains(account.getType())) {
            return 1;
        }
        return LIABILITIES.contains(account.getType()) ? -1 : 0;
    }

    public record History(
            List<Point> netWorth,
            List<AccountSeries> investmentAccounts,
            List<AccountAdded> accountsAdded,
            List<AccountDropped> accountsDropped,
            List<String> leftOutOfNetWorth
    ) {
    }

    public record Point(LocalDate day, BigDecimal value) {
    }

    public record AccountSeries(String accountId, List<Point> points) {
    }

    /**
     * The day an account first counted toward net worth, which the graph marks. Carries the name
     * because the graph also marks accounts that have since been dropped.
     */
    public record AccountAdded(LocalDate day, String accountId, String name) {
    }

    /** The day Plaid stopped returning an account, from which it no longer counts. */
    public record AccountDropped(LocalDate day, String accountId, String name) {
    }
}
