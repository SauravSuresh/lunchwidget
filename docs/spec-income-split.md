# Spec: income, split bills & repayment matching

Consolidates the decisions from the wayfinder map charted 2026-08-07 (since retired
from the issue tracker; this document is now the record). Vocabulary per
[CONTEXT.md](../CONTEXT.md). Prototypes (primary sources): branches
`prototype/split-editor` (receipt variant C won) and `prototype/income-flow`
(waterline variant B won).

## 0. Verified API constraints

Checked against the live v1 API on 2026-08-07. These are why the data model below
looks the way it does — re-verify before designing around any of them again.

- Tags **are** writable on `POST /v1/transactions` (names or ids), and tag names
  auto-create on insert.
- Native splits are **not** creatable at insert: it's `POST` then `PUT` with `split`.
  Split children cannot carry tags — which is what rules native splits out here.
- A transaction cannot be both a split and part of a transaction group.
- `exclude_from_budget`, `exclude_from_totals` and `is_income` are **category-level
  only**. There is no per-transaction flag.
- Lunch Money has no native repayment↔expense matching.
- The official Lunch Money reimbursement pattern is exactly the one used here: the
  owed portion goes to a category excluded from budget and totals.

## 1. What's being added

Three additions to the quick-add loop:

1. **Income (+) entries** — general income and repayments, entered from the same dialog.
2. **Split bills** — a receipt-style split step after the quick-add dialog; only your
   share counts against today's allowance, immediately.
3. **Repayment matching** — enter the amount received; the app shows what it settles,
   oldest-first. No standalone owed screen; pending surfaces only here.

## 2. Data model in Lunch Money

The widget stores **no ledger**. Lunch Money is the single source of truth.

- **Reimbursements category** — a category with `exclude_from_budget: true` and
  `exclude_from_totals: true`. Holds every owed portion and every repayment. Because
  exclusion is category-level in the v1 API (no per-transaction flags), this category
  is what keeps owed money out of budgets, totals, and the allowance.
- **Owed portion** — positive-amount transaction in Reimbursements, tagged
  `owed:<slug>`, payee/note copied from the split. One per person per split.
- **Repayment** — negative-amount transaction in Reimbursements, tagged `owed:<slug>`.
  One per settle, regardless of how many items it covers.
- **Your share** — ordinary transaction in the chosen (tracked) category, exactly like
  today's quick-add.
- **Pending (per person)** = sum of all Reimbursements transactions carrying that
  person's tag (owed positive, repaid negative). May go negative on overpay; a negative
  pending nets against future splits.
- **Person tag** — `owed:<slug>`; slug = display name lowercased, trimmed,
  spaces→hyphen (`Alex P` → `owed:alex-p`). Tag names auto-create on insert (v1);
  no pre-creation call. Display names live only in the widget's local people list.
- **General income** — negative-amount transaction in a user-chosen income category.
  Never touches Reimbursements or the allowance.

Sign convention: v1 default — positive = expense, negative = credit. No
`debit_as_negative` anywhere.

### Colon fallback (verify at provisioning)

Colons in tag names are undocumented but nowhere prohibited. At provisioning, verify
`owed:` with one test insert (then delete it). If rejected, fall back to prefix
`owed-` and store the working prefix in Prefs. All tag parsing uses the stored prefix.

## 3. Provisioning (first split)

Settings gains one field: **Reimbursements category**, default `Reimbursements`.
On first split (and whenever the setting changes):

1. `GET /v1/categories`, look up by name (case-insensitive).
2. Missing → `POST /v1/categories` with both exclude flags true.
3. Present with wrong flags (e.g. user pointed it at an old "loan" category) →
   `PUT /v1/categories/:id` setting both flags. Silent, one-time.
4. Stamp `owed_since = today` in Prefs if not already set.

No migration: pre-existing loan-category transactions have no tags and are ignored.

## 4. People

- Local list in Prefs: `[{display, slug}]`, recency-sorted (same pattern as
  `recentCategoryIds`).
- Picker: type-to-search over the local list; a typed new name is slugified and
  auto-saved on first use.
- **Android contacts** as optional input source: picker search also queries contacts
  (`READ_CONTACTS`, requested lazily on first use; denial degrades to typed names —
  no other behavior change). A picked contact contributes only its display name.
- No merge/rename tooling. Typos are repaired by renaming the tag in Lunch Money's
  web UI (global by tag ID); the widget re-reads on next refresh.

## 5. Split flow (screens)

`prototype/split-editor.html`, variant C — RECEIPT.

1. **Quick-add dialog** (unchanged): amount → category → note. New row: **SPLIT?** →
   opens the split step with the entered amount/category/note.
2. **Receipt step**:
   - Total in dot-matrix at top; note as label.
   - Per-person **proportion bar** (segments per participant, echoes the widget's
     allowance bar; your segment white, others grey).
   - **People picker** (§4) adds/removes participants; **ME** chip toggles include-me
     ("I'm in" vs "I just paid" — when off, your share is 0 and everything is owed).
   - Receipt lines: person · dotted leader · amount, with a lock marker.
   - **YOURS · HITS TODAY** emphasized above SAVE SPLIT.

### Snap engine

- Rows start unlocked (hollow dot). Shares = equal split of what locked rows leave.
- Editing an amount locks it (filled dot); unlocked rows rebalance equally.
- Rounding: unlocked shares floored to 2dp; the remainder lands on the first
  unlocked row.
- EQUAL clears all locks.
- All rows locked with sum ≠ total → red `OFF BY ₹x` warning; SAVE disabled until
  resolved (red #D71921 is signal-only, per house style).

### Save

One `POST /v1/transactions` with the whole batch (the endpoint takes an array):
your share (if > 0) to the chosen category + one owed transaction per person to
Reimbursements with their tag. Status `uncleared`, date today — same as today's
quick-add. Then `RefreshWorker.refreshNow`.

## 6. Income & repayment flow (screens)

`prototype/income-flow.html`, variant B — WATERLINE.

1. **Entry dialog** gains a `−/+` segmented toggle (default `−`, the unchanged
   expense path). `+` swaps the form for **REPAYMENT | INCOME**.
2. **Repayment**: person list — name, `N PENDING · OLDEST <date>`, pending total in
   dot-matrix. Pick one →
3. **Settle screen (waterline)**: RECEIVED amount input, prefilled to full pending.
   Items listed oldest-first (date · payee · amount — payee comes from the owed
   transaction). The amount pours top-down: full items filled, boundary item shows a
   partial fill bar and `PARTIAL · ₹x STAYS PENDING`, untouched items dimmed.
   Tapping a line sets the amount to fill exactly through it (shortcut, not
   selection). Amount above total pending → red overpaid flag, still saveable
   (pending goes negative and nets later).
   - Saves **one** transaction: `−amount` → Reimbursements, person's tag. The
     per-item pour is display-only; nothing per-item is stored.
4. **General income**: amount → income category chips (categories with
   `is_income: true`, recency-sorted) → save `−amount` to that category.

## 7. Pending computation

Per the query-mechanics research (full doc: `docs/research/pending-owed-query.md`
on branch `research/pending-owed-query`):

- Once per refresh (4-hourly + after every quick-add/settle):
  `GET /v1/transactions?category_id={reimbursements}&start_date={owed_since}&end_date={today}&limit=500&offset=…`,
  loop while `has_more`. Explicit dates are mandatory — omitting them defaults to the
  current month and would drop old debts.
- Group client-side by `owed:*` tag name (transactions return `tags: [{id, name}]`),
  sum amounts → pending per person; keep the individual positive transactions as the
  item list (date, payee, amount), oldest-first.
- Cache the per-person map + items as JSON in Prefs beside the existing snapshot;
  all screens read the cache. Volume is trivial (hand-entered rows; one page covers
  years). Not filtered by `tag_id` — that param takes a single numeric ID and would
  need N calls plus a name→ID lookup.

## 8. Allowance interaction

- A split changes spend by **your share only** — owed portions live in an excluded
  category, so the existing allowance math needs no change beyond the category being
  excluded server-side. Verify `Allowance` ignores Reimbursements transactions (they
  arrive in the transaction fetch only if the category is tracked — it isn't).
- Repayments and income never touch the allowance.

## 9. Edge cases

| Case | Behavior |
|---|---|
| Include-me off | Your share 0; no tracked transaction posts; all owed. |
| Overpay | Allowed; pending goes negative; red flag at entry, nets against future splits. |
| All rows locked, bad sum | Red OFF BY, save disabled. |
| Colon rejected in tags | `owed-` prefix fallback, stored in Prefs (§2). |
| Contacts permission denied | Typed names only; never re-prompt aggressively. |
| Person with 0 pending | Not shown in repayment person list. |
| Offline | Same as today: POST fails → error toast, nothing saved. Pending screens read last cache with STALE semantics. |
| Split with 0 people | SPLIT? step unavailable / falls back to plain save. |

## 10. Not in v1

- ~~Itemized receipt split (easychecksplitter-style)~~ — shipped as v2, §12.
- Standalone owed-overview screen; transaction groups; native LM splits; per-person
  asset accounts; contacts binding beyond display names.

## 11. Build order (suggested)

1. Provisioning + Prefs plumbing (§3) and tag prefix verification.
2. Pending fetch + cache (§7) — testable against real data immediately.
3. Split receipt step + snap engine (§5; engine is pure math — unit-test it like
   `AllowanceTest`).
4. Income/repayment flow (§6).
5. Settings row + README update.

## 12. v2: itemized receipt split

An **ITEMIZE** button in the split receipt step (beside EQUAL, needs ≥1 person)
opens the items screen:

- Rows of *amount + optional label*, each with assignee chips (YOU + the split's
  people). Multi-assign shares an item equally among its assignees. Unassigned
  items block DONE.
- Footer shows `ITEMS <Σitems> · BILL <total>` plus the derived adjustment:
  `+x% TAX & FEES` when the bill exceeds the items, `−x% DISCOUNT` when a
  coupon shrinks it.
- **Math** (pure, in `SplitMath.itemize`): per-person subtotal (shared items
  divided by assignee count) scaled by `bill ÷ Σitems`, rounded to 2dp,
  remainder to you. Scaling makes GST/service-charge proration and discounts
  the same operation — CGST/SGST is a flat percentage of the food subtotal, so
  proportional scaling *is* the tax rule; no detection heuristics.
- DONE writes the computed shares back to the receipt rows **locked**; normal
  lock-edit still works, EQUAL wipes back to equal mode. Items persist while
  the dialog lives, so re-entering ITEMIZE resumes them.
- Nothing downstream changes: Lunch Money still receives only the final
  share/owed transactions (§5).
