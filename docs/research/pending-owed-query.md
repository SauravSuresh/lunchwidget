# Pending-owed query mechanics

Research for issue #3. Verified against the Lunch Money v1 API docs
(https://lunchmoney.dev/v1/transactions) on 2026-08-07.

## Recommendation (the plan the spec should mandate)

One extra request cycle inside the existing 4-hour `RefreshWorker` pass:

```
GET /v1/transactions
  ?category_id=<reimbursements_category_id>
  &start_date=<owed_since>          # fixed date stored once at feature setup
  &end_date=<today>
  &limit=500
  &offset=<n*500>                   # loop while has_more == true
```

Then, entirely client-side:

1. Keep transactions whose `tags` array contains a tag named `owed:<person>`.
2. `pendingByPerson = txns.groupBy(personFromTag).mapValues { sum(amount) }`
   — repayments are negative transactions with the same tag, so the plain sum
   is the pending balance; a person at 0 is settled.
3. Persist the map as a JSON string in `Prefs` (same pattern as `snapshot`),
   e.g. `owedSnapshot = {"alice": 450.0, "bob": 120.0}` plus a `fetchedAt`
   date. The widget/UI reads only the cache, never the network.

## Why category_id, not tag_id

Both filters exist on `GET /v1/transactions` — verified param list:

| Param | Docs behavior |
|---|---|
| `tag_id` (number) | "Filter by tag. Only accepts IDs, not names." — **one ID per request** |
| `category_id` (number) | "Filter by category. Will also match category groups." |
| `start_date` / `end_date` (YYYY-MM-DD) | Both required if either given; defaults to current calendar month if omitted |
| `limit` (number) | "Sets the maximum number of records to return." Default 1000; no stated max |
| `offset` (number) | "Sets the offset for the records returned" |
| response | Transaction objects + a `has_more` indicator |

So `tag_id` filtering *is* supported, but it is the worse plan here:

- `tag_id` takes a single numeric ID → **N requests for N persons**, plus a
  prior `GET /v1/tags` call to resolve `owed:<person>` names to IDs, plus
  handling tags that don't exist yet.
- `category_id` is **one known ID, one query, all persons** — the design
  already funnels every owed/repayment row into the excluded "Reimbursements"
  category, and each returned transaction carries its `tags` (with names), so
  grouping by person needs no extra call and no ID resolution.
- The category ID is already available locally: `Prefs.categories` caches the
  full category list every refresh, so the widget can look up "Reimbursements"
  by name without a new endpoint.

Use `tag_id` only if a spec change ever moves owed rows out of a dedicated
category.

## Date window: fixed `owed_since`, not calendar-month, not unbounded-by-default

- Omitting dates defaults to the **current calendar month** — wrong, pending
  items span months.
- Recommend a stored `owed_since` date set once when the feature is first
  configured (i.e., all-time since install). It's the simplest correct window:
  no risk of dropping an old unsettled debt, and volume makes it cheap (below).
- Do not bother with a moving "settled watermark" (advancing `start_date` past
  fully-settled history) unless volume ever demands it — it adds state and a
  correctness hazard (a late repayment before the watermark would corrupt the
  sum) for no measurable gain.

## Pagination cost

Reimbursement rows are hand-entered owed splits and repayments — realistically
tens per month. At 500/page (docs default is actually 1000, no documented max;
mandate an explicit `limit=500` and loop on `has_more` so the plan is robust
either way), even **two years of history is a single page**. Cost per 4-hour
refresh: one HTTP request, occasionally two. Negligible against the two calls
(`categories`, `transactions`) the worker already makes.

## Caching strategy

Mirror the existing snapshot pattern exactly (`Prefs.kt` / `RefreshWorker.kt`):

- `RefreshWorker.doWork()` fetches, computes `Map<String, Double>`
  person → pending, writes it to a new `Prefs.owedSnapshot` JSON property.
- UI reads only `owedSnapshot`; `lastError` semantics unchanged. On fetch
  failure the stale map keeps rendering, same as the allowance snapshot.
- 4-hour staleness is fine: owed balances change only when the user posts a
  split or repayment, and the app already calls `RefreshWorker.refreshNow()`
  after its own writes, which re-syncs immediately.

## Exact mandated query (summary)

`GET /v1/transactions?category_id={reimbursements}&start_date={owed_since}&end_date={today}&limit=500&offset={page*500}`, loop while `has_more`; sum `amount` per `owed:*` tag; cache the per-person map in `Prefs` beside the existing snapshot.
