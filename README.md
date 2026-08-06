# lunchwidget

Android home-screen widget for [Lunch Money](https://lunchmoney.app): shows
today's adaptive daily allowance (same math as
[dailyspend](https://github.com/SauravSuresh/dailyspend), computed on-device)
and quick-adds transactions in two taps.

**Widget:** `₹362 today` · `₹7,964 left · 22d · on track ✓`
Tap → quick-add (amount + category + note). `↻` → refresh.

## Install

```sh
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Open **Lunch Widget** app → paste your Lunch Money API token
(lunchmoney.app → Developers) → Save. Long-press home screen → widgets →
Lunch Widget.

## Settings

- **Tracked categories** — comma-separated, default `Living Expenses`.
  A group category includes all its children.
- **Period start day** — default `29` (29th–28th cycle); `1` = calendar month.
- **Currency symbol** — display only, default `₹`.

## How it works

Every 4 hours (and after each quick-add) a WorkManager job pulls categories,
budget, and period transactions from `dev.lunchmoney.app/v1`, computes
`allowance = (budget − spent) ÷ days left`, caches the snapshot, and redraws
the widget. Works offline from the last snapshot; `!` marks stale data.

Quick-adds post as uncleared transactions dated today.

## Test

```sh
./gradlew test   # allowance math unit tests
```
