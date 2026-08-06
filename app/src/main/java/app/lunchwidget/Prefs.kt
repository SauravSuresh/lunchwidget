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
                )
            }
            sp.edit().putString("categories", arr.toString()).apply()
        }
}
