# Receipt OCR — scan a bill into the itemize screen

Status: v1 spec, 2026-08-18. Lives on branch `feature/receipt-ocr`; maintained
there, not on main, until it earns a merge. Plugs into the itemized split
(spec-income-split.md §12); nothing downstream of the items list changes.

## 0. Constraints this must not violate

Set by the awesome-lunchmoney PR #26 review (see memory: awesome-lunchmoney-listing):

- **No image retention.** Photo → text → discard. The capture file lives in
  `cacheDir`, is deleted immediately after recognition, and is never written
  to the gallery or backed up. README's data-retention wording stays true.
- **README dependency claim changes on this branch.** ML Kit (bundled,
  on-device) breaks "zero third-party runtime dependencies beyond AndroidX
  WorkManager". The branch README says so honestly. Recognition is fully
  on-device; "nothing leaves the device" still holds.
- **data/tools.yml** in awesome-lunchmoney is only updated (neutrally) if and
  when this merges to main and ships.

## 1. What's being added

A **SCAN** button on the itemize screen, beside + ITEM. Tap → system camera
(`ACTION_IMAGE_CAPTURE`, no CAMERA permission needed) → ML Kit on-device text
recognition → parsed `amount + label` rows appended to the items list.
Assignment stays manual (the existing chips). That's all.

The existing §12 machinery absorbs OCR imperfection:

- The bill total is already known (typed in quick-add), and the itemize footer
  already shows `items ₹X vs bill ₹Y` plus the derived tax/discount %. Missed
  or junk lines are immediately visible and fixable by hand.
- Proportional scaling (bill ÷ Σitems) means the parse does not need to catch
  GST/service lines — they are *supposed* to be skipped.

## 2. Components

- **ReceiptParser** (new, pure Kotlin object — unit-tested like SplitMath):
  `parse(lines: List<String>): List<ParsedItem(amount, label)>`.
  - Amount = last numeric token on the line (`123`, `123.00`, `1,234.50`),
    currency markers (₹, Rs, INR) and separator commas stripped; accepted
    range (0, 100 000).
  - Label = everything before the amount, trimmed of dot/dash leaders and
    trailing qty columns; empty label allowed (row still useful).
  - Skip lines carrying settlement/summary words (word-boundary match):
    total, subtotal, gst, cgst, sgst, igst, vat, tax, service, discount,
    round(ing/off), tip, cash, card, upi, change, due, balance, net, gross,
    amount, tender, paid, invoice, bill no, date, table, qty. Skip lines
    whose amount token is a percentage (`2.5%`).
  - Input is ML Kit's lines re-joined into visual rows first: printed
    receipts column-ize (name left, qty/price/amount right) and OCR returns
    the columns as separate lines, so `rows()` groups lines by overlapping
    vertical band (bounding boxes) and joins left-to-right. Real receipts
    demanded this on day one (Chopstix, 2026-08-18 — names and prices came
    back in separate blocks).
  - A colon right before the amount disqualifies the line (`Dine In: 4`,
    `21:47`, `Bill No.: …` are key:value pairs, not priced items), and
    trailing bare-number tokens (qty / unit-price columns) are stripped from
    the label.
- **Scan flow** (QuickAddActivity):
  - SCAN writes to `cacheDir/scan.jpg` via FileProvider, launches the camera,
    recognizes with ML Kit Latin, appends parsed rows to `billItems` (the
    untouched blank placeholder row is dropped), re-renders. Capture file
    deleted in `finally`.
  - Zero rows parsed / recognition error → toast, list untouched.
  - ponytail: itemize state lives in the Activity; if Android kills it behind
    the camera, the split is re-entered by hand. Persist-to-Prefs only if this
    bites in practice.
- **Dependency**: `com.google.mlkit:text-recognition:16.0.1` (bundled Latin
  model, ~4 MB, works sideloaded/offline — the unbundled variant needs Play
  services downloads, wrong fit for this app).
- **Manifest**: FileProvider entry with a cache-path.

## 3. Not in v1

- Importing an existing photo from the gallery (camera only).
- Quantity math (`2 x Naan 240` becomes one ₹240 row; the label keeps the qty text).
- Devanagari / non-Latin receipts.
- Auto-assignment, merchant/date extraction, receipt storage of any kind.

## 4. Build order

1. ReceiptParser + fixture tests (red → green).
2. ML Kit dependency + FileProvider + scan flow behind the SCAN button.
3. Branch README honesty edits (dependency claim, scan-and-discard note).
4. Device verification via adb; parser fixtures carry the regression load.
