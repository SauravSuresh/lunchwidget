package app.lunchwidget

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("lunchwidget", Context.MODE_PRIVATE)

    init {
        // Reading a sealed value re-seals it if it predates encryption, but
        // `people` is only read deep inside the split flow — it would sit in
        // plaintext until the next split. Sweep all three once, then never again.
        if (!sp.getBoolean("sealed_v1", false)) {
            try {
                token; people; pending
            } catch (e: Exception) {
                // Malformed legacy value: leave it, don't take the app down.
            }
            sp.edit().putBoolean("sealed_v1", true).apply()
        }
    }

    // Sealed values: the token, and the people/pending ledger, which carries the
    // real names of everyone who owes you money. Everything else in here is
    // budget arithmetic and settings — no secret to leak.
    private fun sealed(key: String): String {
        val raw = sp.getString(key, "") ?: ""
        if (raw.isEmpty()) return ""
        val plain = Crypto.open(raw)
        // Written before encryption shipped — seal it in place on first read.
        if (!Crypto.isSealed(raw) && plain.isNotEmpty()) {
            sp.edit().putString(key, Crypto.seal(plain)).apply()
        }
        return plain
    }

    private fun putSealed(key: String, value: String) =
        sp.edit().putString(key, if (value.isEmpty()) "" else Crypto.seal(value)).apply()

    var token: String
        get() = sealed("token")
        set(v) = putSealed("token", v)

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

    // A refresh is in flight; the widget swaps ↻ for a spinner while true.
    var refreshing: Boolean
        get() = sp.getBoolean("refreshing", false)
        set(v) = sp.edit().putBoolean("refreshing", v).apply()

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

    // Existing tag names, for the quick-add picker. Newline-joined: tag names can
    // contain commas, which is what the field itself separates on.
    var tags: List<String>
        get() = (sp.getString("tags", "") ?: "").split("\n").filter { it.isNotBlank() }
        set(v) = sp.edit().putString("tags", v.joinToString("\n")).apply()

    // --- accounts ---

    var assets: List<Asset>
        get() {
            val raw = sp.getString("assets", null) ?: return emptyList()
            val arr = JSONArray(raw)
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Asset(o.getLong("id"), o.getString("name"), o.optString("type"))
            }
        }
        set(v) {
            val arr = JSONArray()
            for (a in v) {
                arr.put(JSONObject().put("id", a.id).put("name", a.name).put("type", a.type))
            }
            sp.edit().putString("assets", arr.toString()).apply()
        }

    // 0 = no default; transactions then post without asset_id, as before.
    var defaultAssetId: Long
        get() = sp.getLong("default_asset", 0L)
        set(v) = sp.edit().putLong("default_asset", v).apply()

    // Off hides the date chip everywhere and everything posts today — this is a
    // quick add first, and most adds are for right now.
    var dateEntry: Boolean
        get() = sp.getBoolean("date_entry", true)
        set(v) = sp.edit().putBoolean("date_entry", v).apply()

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
        get() = sealed("people").split("\n")
            .filter { it.contains("|") }
            .map { it.substringBefore("|") to it.substringAfter("|") }
        set(v) = putSealed("people", v.joinToString("\n") { "${it.first}|${it.second}" })

    fun touchPerson(slug: String, display: String) {
        people = listOf(slug to display) + people.filter { it.first != slug }
    }

    var pending: List<PendingPerson>
        get() = sealed("pending").ifEmpty { null }?.let { SplitMath.pendingFromJson(it) }
            ?: emptyList()
        set(v) = putSealed("pending", SplitMath.pendingToJson(v))
}
