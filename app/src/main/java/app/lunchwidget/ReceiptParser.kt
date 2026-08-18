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

    // Bare numeric column token (qty or unit price), for label cleanup.
    private val NUM_TOKEN = Regex("\\d+(?:[.,]\\d+)?")

    fun parse(lines: List<String>): List<ParsedItem> = lines.mapNotNull { raw ->
        val line = raw.trim()
        if (line.isEmpty() || SKIP.containsMatchIn(line)) return@mapNotNull null
        if (line.contains('%')) return@mapNotNull null
        val m = MONEY.find(line) ?: return@mapNotNull null
        val amount = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return@mapNotNull null
        if (amount <= 0.0 || amount >= 100_000.0) return@mapNotNull null
        val prefix = line.substring(0, m.range.first).trimEnd()
        // A colon right before the number is a key:value pair (Dine In: 4,
        // 21:47, Bill No.: …), never a priced item.
        if (prefix.endsWith(':')) return@mapNotNull null
        // Drop trailing qty/unit-price columns so "Signature Miso 1 530.00"
        // labels as "Signature Miso".
        val words = prefix
            .trim { it.isWhitespace() || it == '.' || it == '-' || it == '·' || it == ':' }
            .split(Regex("\\s+")).toMutableList()
        while (words.isNotEmpty() && NUM_TOKEN.matches(words.last())) {
            words.removeAt(words.size - 1)
        }
        ParsedItem(amount, words.joinToString(" ").trim())
    }

    // One OCR'd line with its position on the photo.
    data class OcrLine(val text: String, val top: Int, val bottom: Int, val left: Int)

    /**
     * Rebuild visual receipt rows from OCR lines: printers column-ize (name
     * left, qty/price/amount right) and OCR returns the columns as separate
     * lines, so group by overlapping vertical band and re-join left-to-right.
     */
    fun rows(lines: List<OcrLine>): List<String> {
        val groups = mutableListOf<MutableList<OcrLine>>()
        for (l in lines.sortedBy { it.top + it.bottom }) {
            val cur = groups.lastOrNull()
            if (cur != null) {
                val center = cur.sumOf { (it.top + it.bottom) / 2.0 } / cur.size
                val height = (cur.sumOf { (it.bottom - it.top).toDouble() } / cur.size)
                    .coerceAtLeast(1.0)
                if (Math.abs((l.top + l.bottom) / 2.0 - center) < 0.6 * height) {
                    cur.add(l)
                    continue
                }
            }
            groups.add(mutableListOf(l))
        }
        return groups.map { g -> g.sortedBy { it.left }.joinToString(" ") { it.text } }
    }
}
