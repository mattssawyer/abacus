# Balance history comes only from our own snapshots

Plaid reports only today's balances. It has no past balances or security prices, so we record a balance snapshot for every account inside each item sync, at most one per account per day, and draw the investment and net worth graphs from those alone. The graphs start on the day an account is linked, and a day we didn't record can't be recovered later.

## Considered Options

- **Rebuilding investment history from Plaid's 24 months of investment transactions.** Rejected for now. It needs an outside price source, and it can't price 401(k) funds that have no ticker (Plaid no longer returns CUSIP or ISIN). It could come back later as an estimated backfill, drawn differently from recorded snapshots.
- **Rebuilding bank and card balances by walking transactions back from today's balance.** Rejected. Cash would have history and investments wouldn't, so net worth would show a false jump on the day investments were linked.
- **A daily scheduled snapshot job.** Rejected. Every balance change arrives through an item sync, so a job would only add rows repeating yesterday's value. The graph carries each account's last value forward instead, until the account stops appearing in syncs.

## Consequences

- Net worth jumps when an account is linked or dropped. The graph marks those days rather than hiding the jump.
- Without webhooks (local runs with no tunnel), balances never change after linking, so the graphs stay flat.
