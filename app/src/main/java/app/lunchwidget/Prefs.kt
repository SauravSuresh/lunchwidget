package app.lunchwidget

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("lunchwidget", Context.MODE_PRIVATE)

    var token: String
        get() = sp.getString("token", "") ?: ""
        set(v) = sp.edit().putString("token", v).apply()

    var trackedCategories: List<String>
        get() = (sp.getString("tracked", "Living Expenses") ?: "")
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }
        set(v) = sp.edit().putString("tracked", v.joinToString(",")).apply()

    var startDay: Int
        get() = sp.getInt("start_day", 29)
        set(v) = sp.edit().putInt("start_day", v).apply()

    var currency: String
        get() = sp.getString("currency", "₹") ?: "₹"
        set(v) = sp.edit().putString("currency", v).apply()

    var recentCategoryIds: List<Long>
        get() = (sp.getString("recent_cats", "") ?: "")
            .split(",").mapNotNull { it.toLongOrNull() }
        set(v) = sp.edit().putString("recent_cats", v.joinToString(",")).apply()

    var lastError: Boolean
        get() = sp.getBoolean("last_error", false)
        set(v) = sp.edit().putBoolean("last_error", v).apply()

    var snapshot: Snapshot?
        get() {
            val raw = sp.getString("snapshot", null) ?: return null
            val o = JSONObject(raw)
            return Snapshot(
                periodStart = LocalDate.parse(o.getString("periodStart")),
                periodEnd = LocalDate.parse(o.getString("periodEnd")),
                totalBudget = o.getDouble("totalBudget"),
                spent = o.getDouble("spent"),
                remaining = o.getDouble("remaining"),
                daysLeft = o.getInt("daysLeft"),
                allowance = o.getDouble("allowance"),
                paceDelta = o.getDouble("paceDelta"),
                onTrack = o.getBoolean("onTrack"),
                allowanceToday = o.optDouble("allowanceToday", 0.0),
                spentToday = o.optDouble("spentToday", 0.0),
            )
        }
        set(v) {
            if (v == null) {
                sp.edit().remove("snapshot").apply(); return
            }
            val o = JSONObject()
                .put("periodStart", v.periodStart.toString())
                .put("periodEnd", v.periodEnd.toString())
                .put("totalBudget", v.totalBudget)
                .put("spent", v.spent)
                .put("remaining", v.remaining)
                .put("daysLeft", v.daysLeft)
                .put("allowance", v.allowance)
                .put("paceDelta", v.paceDelta)
                .put("onTrack", v.onTrack)
                .put("allowanceToday", v.allowanceToday)
                .put("spentToday", v.spentToday)
            sp.edit().putString("snapshot", o.toString()).apply()
        }

    var categories: List<Category>
        get() {
            val raw = sp.getString("categories", null) ?: return emptyList()
            val arr = JSONArray(raw)
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Category(
                    id = o.getLong("id"),
                    name = o.getString("name"),
                    groupId = if (o.isNull("groupId")) null else o.getLong("groupId"),
                    isGroup = o.getBoolean("isGroup"),
                    isIncome = o.optBoolean("isIncome", false),
                    excluded = o.optBoolean("excluded", false),
                )
            }
        }
        set(v) {
            val arr = JSONArray()
            for (c in v) {
                arr.put(
                    JSONObject()
                        .put("id", c.id)
                        .put("name", c.name)
                        .put("groupId", c.groupId)
                        .put("isGroup", c.isGroup)
                        .put("isIncome", c.isIncome)
                        .put("excluded", c.excluded)
                )
            }
            sp.edit().putString("categories", arr.toString()).apply()
        }

    // --- income / split / repayment (docs/spec-income-split.md) ---

    var reimbName: String
        get() = sp.getString("reimb_name", "Reimbursements") ?: "Reimbursements"
        set(v) = sp.edit().putString("reimb_name", v.ifBlank { "Reimbursements" }).apply()

    var reimbCategoryId: Long
        get() = sp.getLong("reimb_id", 0L)
        set(v) = sp.edit().putLong("reimb_id", v).apply()

    var owedSince: LocalDate?
        get() = sp.getString("owed_since", null)?.let { LocalDate.parse(it) }
        set(v) = sp.edit().putString("owed_since", v?.toString()).apply()

    // "owed:" unless the colon turned out illegal in tag names (then "owed-").
    var tagPrefix: String
        get() = sp.getString("tag_prefix", "owed:") ?: "owed:"
        set(v) = sp.edit().putString("tag_prefix", v).apply()

    var tagPrefixVerified: Boolean
        get() = sp.getBoolean("tag_prefix_verified", false)
        set(v) = sp.edit().putBoolean("tag_prefix_verified", v).apply()

    // Recency-ordered "slug|display" pairs; display names never leave the device.
    var people: List<Pair<String, String>>
        get() = (sp.getString("people", "") ?: "").split("\n")
            .filter { it.contains("|") }
            .map { it.substringBefore("|") to it.substringAfter("|") }
        set(v) = sp.edit()
            .putString("people", v.joinToString("\n") { "${it.first}|${it.second}" })
            .apply()

    fun touchPerson(slug: String, display: String) {
        people = listOf(slug to display) + people.filter { it.first != slug }
    }

    var pending: List<PendingPerson>
        get() = sp.getString("pending", null)?.let { SplitMath.pendingFromJson(it) } ?: emptyList()
        set(v) = sp.edit().putString("pending", SplitMath.pendingToJson(v)).apply()
}
