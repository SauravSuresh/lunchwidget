# lunchwidget — Android daily-allowance widget for Lunch Money

## Purpose
Home-screen widget that shows today's adaptive daily allowance (dailyspend's
math, computed on-device) and lets you quick-add a transaction to Lunch Money
in two taps.

## Scope
Standalone widget-only Android app. Kotlin, single module, min SDK 26,
sideloaded APK. Not a fork of lunch_money_companion; no server dependency.

## Components
- **LunchMoneyApi** — direct HTTPS to `https://dev.lunchmoney.app/v1` with
  bearer token. `GET /categories`, `GET /budgets`, `GET /transactions`
  (paginated), `POST /transactions` (insert quick-add). HttpURLConnection +
  org.json — no HTTP/JSON libraries.
- **Allowance** — pure function port of dailyspend `compute.py`:
  - period = custom start day (default 29 → 29th–28th cycle; 1 = calendar month)
  - tracked categories (default "Living Expenses") expanded to include group
    children
  - total_budget = sum of `budget_amount` for period key; spent = sum of
    abs(amount) for tracked transactions; remaining = budget − spent
  - days_left = (period_end − today), min 1; allowance = remaining / days_left
  - pace: expected = budget × elapsed/total days; on_track = expected ≥ spent
- **SpendWidget** — classic `AppWidgetProvider` (RemoteViews, no Compose/Glance):
  big allowance number, subline "₹X left · N days · on track ✓ / over pace",
  tap → QuickAddActivity. Renders last cached snapshot; works offline.
- **QuickAddActivity** — dialog-themed: amount, category spinner (cached list),
  optional note, Save → POST insert (today's date, uncleared) → refresh →
  widget updates.
- **SettingsActivity** — launcher activity: API token, tracked categories
  (comma-sep), period start day, currency symbol. Saved in SharedPreferences.
- **RefreshWorker** — WorkManager periodic (4 h) + one-shot after quick-add /
  widget refresh tap: fetch, compute, cache snapshot, update widget.

## Storage
SharedPreferences: settings + last snapshot JSON + cached categories JSON.

## Errors
- No token → widget shows "Tap to set up" → opens Settings.
- API/network failure → keep stale snapshot, show "!" marker.
- Quick-add failure → toast with error, nothing saved.

## Testing
JVM unit tests for Allowance math (period boundaries, group expansion, last-day
guard, pace). Everything else verified on device via adb.

## Out of scope
Play Store release, multi-currency conversion, editing/deleting transactions,
account (asset) selection on quick-add, auth beyond a pasted token.
