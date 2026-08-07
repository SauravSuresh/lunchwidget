package app.lunchwidget

import java.time.LocalDate

// Provisioning + tag-prefix verification for the Reimbursements category (spec §2–3).
object Reimbursements {

    // Look the configured category up by name; create it (or fix its exclude flags)
    // if needed. Caches the id and stamps owed_since on first use. Network — call
    // off the main thread.
    fun ensureCategory(api: LunchMoneyApi, prefs: Prefs): Long {
        if (prefs.reimbCategoryId != 0L) return prefs.reimbCategoryId
        val existing = api.categories()
            .firstOrNull { !it.isGroup && it.name.equals(prefs.reimbName, ignoreCase = true) }
        val id = when {
            existing == null -> api.createCategory(prefs.reimbName)
            !existing.excluded -> {
                api.excludeCategory(existing.id); existing.id
            }
            else -> existing.id
        }
        prefs.reimbCategoryId = id
        if (prefs.owedSince == null) prefs.owedSince = LocalDate.now()
        return id
    }

    // Colons in tag names are undocumented (spec §2): after the first real split,
    // read one owed transaction back; if "owed:x" didn't survive as a single tag,
    // rewrite this split's tags with "owed-" and switch the prefix for good.
    fun verifyTagPrefix(api: LunchMoneyApi, prefs: Prefs, txnIds: List<Long>, slugs: List<String>) {
        if (prefs.tagPrefixVerified || txnIds.isEmpty()) return
        val ok = try {
            api.transactionTags(txnIds[0]).contains(prefs.tagPrefix + slugs[0])
        } catch (e: Exception) {
            return // verify again on the next split
        }
        if (!ok) {
            prefs.tagPrefix = "owed-"
            txnIds.zip(slugs).forEach { (id, slug) ->
                api.setTransactionTags(id, listOf(prefs.tagPrefix + slug))
            }
        }
        prefs.tagPrefixVerified = true
    }
}
