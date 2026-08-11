package app.lunchwidget

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.time.LocalDate
import java.util.concurrent.TimeUnit

class RefreshWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        if (prefs.token.isBlank()) return Result.success()
        try {
            val api = LunchMoneyApi(prefs.token)
            val categories = api.categories()
            prefs.categories = categories
            prefs.assets = api.assets()
            // The picker list is a convenience; the allowance is the product. A
            // tags outage must not send the widget to STALE.
            try {
                prefs.tags = api.tags()
            } catch (e: Exception) {
                // keep whatever was cached
            }
            val today = LocalDate.now()
            val (start, end) = Allowance.period(today, prefs.startDay)
            val trackedIds = Allowance.trackedIds(categories, prefs.trackedCategories)
            val budget = api.budgetTotal(start, end, trackedIds)
            val txns = api.transactions(start, end)
            val tracked = txns.filter { it.categoryId in trackedIds && it.amount != 0.0 }
            val todayKey = today.toString()
            val spentToday = tracked.filter { it.date == todayKey }.sumOf { Math.abs(it.amount) }
            val spentBefore = tracked.filter { it.date != todayKey }.sumOf { Math.abs(it.amount) }
            // Category ids ordered by most recent use this period, for the quick-add list.
            prefs.recentCategoryIds = txns
                .filter { it.categoryId != null }
                .groupBy { it.categoryId!! }
                .mapValues { (_, ts) -> ts.maxOf { it.date } }
                .entries.sortedByDescending { it.value }
                .map { it.key }
            prefs.snapshot = Allowance.compute(today, prefs.startDay, budget, spentBefore, spentToday)
            // Pending owed per person, from the Reimbursements category (spec §7).
            val reimbId = prefs.reimbCategoryId
            val owedSince = prefs.owedSince
            if (reimbId != 0L && owedSince != null) {
                // end_date must be strictly after start_date (v1 rejects same-day),
                // and owed_since == today on the day the feature is first used.
                prefs.pending = SplitMath.groupPending(
                    api.categoryTransactions(reimbId, owedSince, today.plusDays(1)),
                    prefs.tagPrefix,
                )
            }
            prefs.lastError = false
        } catch (e: Exception) {
            prefs.lastError = true
            SpendWidget.updateAll(applicationContext)
            return if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
        SpendWidget.updateAll(applicationContext)
        return Result.success()
    }

    companion object {
        fun refreshNow(context: Context) {
            WorkManager.getInstance(context)
                .enqueue(OneTimeWorkRequestBuilder<RefreshWorker>().build())
        }

        fun schedulePeriodic(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "refresh",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<RefreshWorker>(4, TimeUnit.HOURS).build()
            )
        }
    }
}
