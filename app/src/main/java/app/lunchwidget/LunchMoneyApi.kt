package app.lunchwidget

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

class LunchMoneyApi(private val token: String) {

    private fun request(method: String, path: String, body: JSONObject? = null): String {
        val conn = URL("https://dev.lunchmoney.app/v1$path").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.connectTimeout = 30_000
            conn.readTimeout = 30_000
            if (body != null) {
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText() ?: ""
            if (code !in 200..299) throw ApiException("HTTP $code: ${text.take(200)}")
            return text
        } finally {
            conn.disconnect()
        }
    }

    fun categories(): List<Category> {
        val arr = JSONObject(request("GET", "/categories")).getJSONArray("categories")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Category(
                id = o.getLong("id"),
                name = o.getString("name"),
                groupId = if (o.isNull("group_id")) null else o.getLong("group_id"),
                isGroup = o.optBoolean("is_group", false),
                isIncome = o.optBoolean("is_income", false),
                excluded = o.optBoolean("exclude_from_budget", false) &&
                    o.optBoolean("exclude_from_totals", false),
            )
        }
    }

    fun createCategory(name: String): Long {
        val body = JSONObject()
            .put("name", name)
            .put("exclude_from_budget", true)
            .put("exclude_from_totals", true)
        return JSONObject(request("POST", "/categories", body)).getLong("category_id")
    }

    fun excludeCategory(id: Long) {
        val body = JSONObject()
            .put("exclude_from_budget", true)
            .put("exclude_from_totals", true)
        request("PUT", "/categories/$id", body)
    }

    fun budgetTotal(start: LocalDate, end: LocalDate, trackedIds: Set<Long>): Double {
        val arr = JSONArray(request("GET", "/budgets?start_date=$start&end_date=$end"))
        val periodKey = start.toString()
        var total = 0.0
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.isNull("category_id")) continue
            if (o.getLong("category_id") !in trackedIds) continue
            val vals = o.optJSONObject("data")?.optJSONObject(periodKey) ?: continue
            val amt = vals.optDouble("budget_amount")
            if (!amt.isNaN()) total += amt
        }
        return total
    }

    fun transactions(start: LocalDate, end: LocalDate): List<Txn> {
        val out = mutableListOf<Txn>()
        var offset = 0
        val limit = 500
        while (true) {
            val json = request(
                "GET",
                "/transactions?start_date=$start&end_date=$end&limit=$limit&offset=$offset"
            )
            val batch = JSONObject(json).getJSONArray("transactions")
            for (i in 0 until batch.length()) {
                val t = batch.getJSONObject(i)
                val amt = t.optDouble("amount")
                out.add(
                    Txn(
                        categoryId = if (t.isNull("category_id")) null else t.getLong("category_id"),
                        amount = if (amt.isNaN()) 0.0 else amt,
                        date = t.optString("date"),
                    )
                )
            }
            if (batch.length() < limit) break
            offset += limit
        }
        return out
    }

    // Pending fetch: one category, explicit dates (the API defaults to the current
    // month without them, which would drop old debts), tags included.
    fun categoryTransactions(categoryId: Long, start: LocalDate, end: LocalDate): List<PendingTxn> {
        val out = mutableListOf<PendingTxn>()
        var offset = 0
        val limit = 500
        while (true) {
            val json = request(
                "GET",
                "/transactions?category_id=$categoryId&start_date=$start&end_date=$end" +
                    "&limit=$limit&offset=$offset"
            )
            val batch = JSONObject(json).getJSONArray("transactions")
            for (i in 0 until batch.length()) {
                val t = batch.getJSONObject(i)
                val tags = t.optJSONArray("tags") ?: JSONArray()
                out.add(
                    PendingTxn(
                        date = t.optString("date"),
                        payee = t.optString("payee"),
                        amount = t.optDouble("amount", 0.0),
                        tags = (0 until tags.length()).map { j ->
                            tags.getJSONObject(j).getString("name")
                        },
                    )
                )
            }
            if (batch.length() < limit) break
            offset += limit
        }
        return out
    }

    fun insertTransaction(date: LocalDate, amount: Double, categoryId: Long, note: String?) {
        insertTransactions(listOf(NewTxn(date, amount, categoryId, note)))
    }

    // Batch insert; returns the new transaction ids. Tag names auto-create (v1).
    fun insertTransactions(txns: List<NewTxn>): List<Long> {
        val arr = JSONArray()
        for (t in txns) {
            val o = JSONObject()
                .put("date", t.date.toString())
                .put("amount", t.amount)
                .put("category_id", t.categoryId)
                .put("payee", if (t.note.isNullOrBlank()) "Quick add" else t.note)
                .put("status", "uncleared")
            if (t.tags.isNotEmpty()) o.put("tags", JSONArray(t.tags))
            arr.put(o)
        }
        val ids = JSONObject(request("POST", "/transactions", JSONObject().put("transactions", arr)))
            .getJSONArray("ids")
        if (ids.length() != txns.size) {
            throw ApiException("Posted ${txns.size} transactions, server accepted ${ids.length()}")
        }
        return (0 until ids.length()).map { ids.getLong(it) }
    }

    fun transactionTags(id: Long): List<String> {
        val tags = JSONObject(request("GET", "/transactions/$id")).optJSONArray("tags")
            ?: return emptyList()
        return (0 until tags.length()).map { tags.getJSONObject(it).getString("name") }
    }

    fun setTransactionTags(id: Long, tags: List<String>) {
        val body = JSONObject().put("transaction", JSONObject().put("tags", JSONArray(tags)))
        request("PUT", "/transactions/$id", body)
    }
}

class ApiException(message: String) : RuntimeException(message)

data class Txn(val categoryId: Long?, val amount: Double, val date: String)

data class PendingTxn(val date: String, val payee: String, val amount: Double, val tags: List<String>)

data class NewTxn(
    val date: LocalDate,
    val amount: Double,
    val categoryId: Long,
    val note: String?,
    val tags: List<String> = emptyList(),
)
