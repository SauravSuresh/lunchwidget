package app.lunchwidget

import org.json.JSONArray
import org.json.JSONObject

// One participant row in the split editor.
data class Share(
    val slug: String,
    val display: String,
    val isMe: Boolean,
    var locked: Boolean = false,
    var amount: Double = 0.0,
)

// One unsettled owed transaction, as shown on the settle screen.
data class OwedItem(val date: String, val payee: String, val amount: Double)

// How much of an item a poured amount covers.
data class Poured(val item: OwedItem, val take: Double) {
    val frac: Double get() = if (item.amount <= 0) 0.0 else take / item.amount
}

data class PendingPerson(val slug: String, val total: Double, val items: List<OwedItem>)

// Pure math for splits and repayments — same rules as the prototypes, testable on the JVM.
object SplitMath {

    fun round2(x: Double): Double = Math.round(x * 100.0) / 100.0

    fun slugify(name: String): String =
        name.trim().lowercase().replace(Regex("\\s+"), "-")

    /**
     * Equal-split what the locked rows leave across the unlocked rows, mutating their
     * amounts. Unlocked shares floor to 2dp; the rounding remainder lands on the first
     * unlocked row. Returns the off-by amount — nonzero only when every row is locked
     * and the locked sum misses the total.
     */
    fun rebalance(total: Double, shares: List<Share>): Double {
        val free = shares.filter { !it.locked }
        val left = round2(total - shares.filter { it.locked }.sumOf { it.amount })
        if (free.isNotEmpty()) {
            val base = Math.floor(left / free.size * 100.0) / 100.0
            free.forEach { it.amount = base }
            free[0].amount = round2(base + round2(left - base * free.size))
        }
        return round2(total - shares.sumOf { it.amount })
    }

    // Pour an amount over items oldest-first: full items fill, the boundary item partially.
    fun pour(items: List<OwedItem>, amount: Double): List<Poured> {
        var left = amount
        return items.map {
            val take = round2(minOf(left, it.amount).coerceAtLeast(0.0))
            left = round2(left - take)
            Poured(it, take)
        }
    }

    /**
     * Group the Reimbursements category's transactions into pending per person.
     * Owed portions are positive, repayments negative; total = their sum. The item
     * list is what's still unsettled: past repayments pour over the owed items
     * oldest-first, and only remainders survive.
     */
    fun groupPending(txns: List<PendingTxn>, tagPrefix: String): List<PendingPerson> {
        return txns
            .flatMap { t -> t.tags.filter { it.startsWith(tagPrefix) }.map { it to t } }
            .groupBy({ it.first }, { it.second })
            .map { (tag, ts) ->
                val owed = ts.filter { it.amount > 0 }.sortedBy { it.date }
                    .map { OwedItem(it.date, it.payee, round2(it.amount)) }
                val repaid = round2(-ts.filter { it.amount < 0 }.sumOf { it.amount })
                val remaining = pour(owed, repaid)
                    .filter { it.take < it.item.amount }
                    .map { OwedItem(it.item.date, it.item.payee, round2(it.item.amount - it.take)) }
                PendingPerson(
                    slug = tag.removePrefix(tagPrefix),
                    total = round2(ts.sumOf { it.amount }),
                    items = remaining,
                )
            }
    }

    fun pendingToJson(pending: List<PendingPerson>): String {
        val arr = JSONArray()
        for (p in pending) {
            val items = JSONArray()
            for (i in p.items) {
                items.put(JSONObject().put("date", i.date).put("payee", i.payee).put("amount", i.amount))
            }
            arr.put(JSONObject().put("slug", p.slug).put("total", p.total).put("items", items))
        }
        return arr.toString()
    }

    fun pendingFromJson(raw: String): List<PendingPerson> {
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val items = o.getJSONArray("items")
            PendingPerson(
                slug = o.getString("slug"),
                total = o.getDouble("total"),
                items = (0 until items.length()).map { j ->
                    val it = items.getJSONObject(j)
                    OwedItem(it.getString("date"), it.getString("payee"), it.getDouble("amount"))
                },
            )
        }
    }
}
