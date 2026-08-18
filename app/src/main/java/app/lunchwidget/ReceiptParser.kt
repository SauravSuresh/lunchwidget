package app.lunchwidget

// One OCR'd receipt row worth turning into an itemize row.
data class ParsedItem(val amount: Double, val label: String)

/**
 * Turns OCR text lines into amount+label rows for the itemize screen
 * (spec-receipt-ocr.md §2). Deliberately incomplete: summary/tax lines are
 * skipped on purpose — the itemize screen's bill ÷ Σitems scaling is what
 * applies GST/service, so only genuine item lines belong here.
 */
object ReceiptParser {

    // Settlement/summary vocabulary; any whole-word hit disqualifies the line.
    private val SKIP = Regex(
        "\\b(total|subtotal|sub|gst|cgst|sgst|igst|vat|tax|service|svc|discount|disc|" +
            "round(ing)?|off|tip|cash|card|upi|change|due|balance|net|gross|amount|" +
            "tender|paid|invoice|bill|date|table|qty)\\b",
        RegexOption.IGNORE_CASE
    )

    // Trailing money token: optional currency marker, digits with optional
    // thousands-commas and 1-2 decimals. Anchored to end of line.
    // The lookbehind keeps it from biting the tail of a longer token
    // (phone numbers, GSTIN codes ending in digits).
    private val MONEY = Regex(
        "(?<![\\w,.])(?:₹|rs\\.?|inr)?\\s*(\\d{1,3}(?:,\\d{2,3})*(?:\\.\\d{1,2})?)\\s*$",
        RegexOption.IGNORE_CASE
    )

    fun parse(lines: List<String>): List<ParsedItem> = lines.mapNotNull { raw ->
        val line = raw.trim()
        if (line.isEmpty() || SKIP.containsMatchIn(line)) return@mapNotNull null
        if (line.contains('%')) return@mapNotNull null
        val m = MONEY.find(line) ?: return@mapNotNull null
        val amount = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return@mapNotNull null
        if (amount <= 0.0 || amount >= 100_000.0) return@mapNotNull null
        val label = line.substring(0, m.range.first)
            .trim { it.isWhitespace() || it == '.' || it == '-' || it == '·' || it == ':' }
        ParsedItem(amount, label)
    }
}
