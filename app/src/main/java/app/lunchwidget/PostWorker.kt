package app.lunchwidget

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Every write to Lunch Money goes through here. The read path already survived a
 * dead network — it retries and the widget renders its cached snapshot — while a
 * write was a bare thread that showed a toast and forgot. Now WorkManager owns the
 * retry and the network constraint, so an expense logged in a lift posts itself
 * when there's signal again.
 */
class PostWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        if (prefs.token.isBlank()) return Result.failure()
        val txns = decode(inputData.getString(KEY_TXNS) ?: return Result.failure())
        if (txns.isEmpty()) return Result.success()
        return try {
            val api = LunchMoneyApi(prefs.token)
            val ids = api.insertTransactions(resolveReimbursements(api, prefs, txns))
            inputData.getStringArray(KEY_SLUGS)?.let { slugs ->
                Reimbursements.verifyTagPrefix(
                    api, prefs, ids.drop(inputData.getInt(KEY_TAG_OFFSET, 0)), slugs.toList()
                )
            }
            RefreshWorker.refreshNow(applicationContext)
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < MAX_ATTEMPTS) return Result.retry()
            // Out of retries: raise the same STALE marker a failed refresh raises,
            // so a write that never landed isn't invisible.
            prefs.lastError = true
            SpendWidget.updateAll(applicationContext)
            Result.failure()
        }
    }

    // Callers post owed portions and repayments against REIMBURSEMENTS without
    // knowing whether that category exists yet — looking it up costs a request, so
    // it happens here rather than on the dialog's thread.
    private fun resolveReimbursements(
        api: LunchMoneyApi,
        prefs: Prefs,
        txns: List<NewTxn>,
    ): List<NewTxn> {
        if (txns.none { it.categoryId == REIMBURSEMENTS }) return txns
        val id = Reimbursements.ensureCategory(api, prefs)
        return txns.map { if (it.categoryId == REIMBURSEMENTS) it.copy(categoryId = id) else it }
    }

    companion object {
        /** Stand-in category id: the worker swaps it for the real Reimbursements id. */
        const val REIMBURSEMENTS = 0L

        private const val KEY_TXNS = "txns"
        private const val KEY_SLUGS = "slugs"
        private const val KEY_TAG_OFFSET = "tag_offset"
        private const val MAX_ATTEMPTS = 5

        /**
         * @param slugs people whose owed portions are in [txns], for the one-time
         *   tag-prefix check; null for every path that isn't a split.
         * @param tagOffset how many leading transactions are *not* owed portions.
         */
        fun enqueue(
            context: Context,
            txns: List<NewTxn>,
            slugs: List<String>? = null,
            tagOffset: Int = 0,
        ) {
            val data = Data.Builder()
                .putString(KEY_TXNS, encode(txns))
                .putInt(KEY_TAG_OFFSET, tagOffset)
            if (slugs != null) data.putStringArray(KEY_SLUGS, slugs.toTypedArray())
            WorkManager.getInstance(context).enqueue(
                OneTimeWorkRequestBuilder<PostWorker>()
                    .setInputData(data.build())
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build()
            )
        }

        internal fun encode(txns: List<NewTxn>): String {
            val arr = JSONArray()
            for (t in txns) {
                arr.put(
                    JSONObject()
                        .put("date", t.date.toString())
                        .put("amount", t.amount)
                        .put("categoryId", t.categoryId)
                        .put("note", t.note ?: "")
                        .put("tags", JSONArray(t.tags))
                        .put("assetId", t.assetId)
                )
            }
            return arr.toString()
        }

        internal fun decode(raw: String): List<NewTxn> {
            val arr = JSONArray(raw)
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val tags = o.getJSONArray("tags")
                NewTxn(
                    date = LocalDate.parse(o.getString("date")),
                    amount = o.getDouble("amount"),
                    categoryId = o.getLong("categoryId"),
                    note = o.getString("note").ifBlank { null },
                    tags = (0 until tags.length()).map { tags.getString(it) },
                    assetId = o.getLong("assetId"),
                )
            }
        }
    }
}
