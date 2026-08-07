# Ubiquitous language

Glossary for lunchwidget. Terms mean exactly this everywhere — code, tickets, spec.

- **Daily allowance** — (budget − spent before today) ÷ days left in period. The hero number.
- **Split bill** — one real-world payment the user made where others owe them part of it.
- **Share** — the user's own portion of a split bill. The only part that counts against the daily allowance.
- **Owed portion** — the part of a split bill others owe. Posts to the Reimbursements category, tagged per person; never touches the allowance.
- **Reimbursements category** — the Lunch Money category (excluded from budget and totals) holding all owed portions and repayments. Named in settings, default `Reimbursements`.
- **Person** — whoever owes the user money. A plain string name; identity lives in the transaction tag (scheme: issue #4).
- **Repayment** — money received against pending owed portions. A negative-amount transaction in the Reimbursements category, tagged with the person.
- **General income** — money received that settles nothing. Ordinary income transaction, outside the Reimbursements category.
- **Pending** — per person: sum of their owed portions minus their repayments. Computed from Lunch Money; the widget keeps no ledger.
- **owed_since** — fixed date stamped at feature setup. Pending queries start here; nothing earlier is tracked.
