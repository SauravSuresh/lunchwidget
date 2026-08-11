# Map: multi-category display

**Unfinished effort.** Decisions below are settled; the open questions are not.
Charted 2026-08-10 as a wayfinder map on the issue tracker, moved here when the
tracker was retired. When the open questions are answered, this becomes
`docs/spec-multi-category.md` in the shape of `spec-income-split.md`.

## Destination

The primary allowance bar always on screen, with a page per other category you've
actually spent in this period.

## Facts that constrain it

Verified 2026-08-10 against the live account. Figures deliberately omitted — this
repo is public.

- **Budgets live on groups, not leaves.** Living Expenses, Recreation, Personal
  Care and Wants each carry a budget. `cinema`, inside Recreation, carries none of
  its own — so a "cinema bar" has no budget to pace against.
- **Budget-bearing categories with no group also exist**: Bike EMI, Trip, Short
  Film, Emergency Fund. Leaves *and* budget units at once.
- **The widget is RemoteViews.** No free-form swipe. The only real mechanisms are
  ViewFlipper (tap + slide animation), StackView (real swipe, card-deck look), and
  ListView (vertical scroll, all bars visible at once).

## Decisions

- **What a page is** — a budget-bearing group, not the leaf you posted into. A
  cinema transaction surfaces a RECREATION page. Every page gets a real bar.
- **Page lifecycle** — automatic. Any qualifying unit with spend > 0 this period
  gets a page; the set resets each period. No settings list, no configuration.
- **Bar math** — the primary page keeps the daily-allowance math. Secondary pages
  show a **period** bar: spent vs budget, hero = remaining this period. The primary
  tracked group's budget is more than an order of magnitude larger than the
  smallest group budgets; dividing those by days-in-period gives a daily figure
  small enough to be noise. Small budgets pace monthly.
- **Gesture** — tap-to-cycle with a slide animation (ViewFlipper), pending
  prototype confirmation. StackView rejected: the card-deck visual breaks the flat,
  full-bleed Nothing look. ListView rejected: the primary number stops being solo.

## Open questions

1. **Tap already opens quick add — what pays for tap-to-cycle?** The whole widget
   is one button today; ViewFlipper wants the same gesture. Candidates: split the
   tap by region (hero opens quick add, a page-dot strip cycles); a ⌄ chevron next
   to the existing ↻; cycle on tap and move quick-add to a small + target. This
   also forces where the page index persists — RemoteViews holds no client state,
   so cycling means a broadcast to `SpendWidget` that rebuilds with the next
   `setDisplayedChild`. Decide whether a refresh resets to the primary page.
2. **Budget-bearing categories with no group: page or not?** State the eligibility
   rule once so it covers every category: does a budget-bearing top-level leaf get
   its own page, and does a category with spend but no budget anywhere in its chain
   get one (paced against what)? This also decides whether an excluded category
   (Reimbursements) can ever surface a page, and whether Living Expenses is exempt
   from the rule or just the first page it produces.
3. **Secondary page layout** — needs a throwaway prototype. What's on the page,
   how a period bar reads differently from the daily bar at a glance, and whether
   the slide animation feels like paging or like a glitch on a real home screen.
   Blocked by 1 and 2.

## Not yet specified

- Ordering of secondary pages, and a ceiling past which the widget stops being
  glanceable.
- Where the STALE marker and ↻ live once the widget has pages.
- Cache shape: `Prefs.snapshot` holds one snapshot; N pages needs a different
  store, and the refresh job has to compute per-page budget and spend.

## Out of scope

- Changing the daily-allowance math itself.
- Multiple widget instances with different primary categories; resize-aware layouts.
