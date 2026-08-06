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
            )
        }
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

    fun insertTransaction(date: LocalDate, amount: Double, categoryId: Long, note: String?) {
        val txn = JSONObject()
            .put("date", date.toString())
            .put("amount", amount)
            .put("category_id", categoryId)
            .put("payee", if (note.isNullOrBlank()) "Quick add" else note)
            .put("status", "uncleared")
        val body = JSONObject().put("transactions", JSONArray().put(txn))
        request("POST", "/transactions", body)
    }
}

class ApiException(message: String) : RuntimeException(message)

data class Txn(val categoryId: Long?, val amount: Double, val date: String)
