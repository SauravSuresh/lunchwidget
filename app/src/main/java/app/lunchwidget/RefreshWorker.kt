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
            val today = LocalDate.now()
            val (start, end) = Allowance.period(today, prefs.startDay)
            val trackedIds = Allowance.trackedIds(categories, prefs.trackedCategories)
            val budget = api.budgetTotal(start, end, trackedIds)
            val spent = api.spent(start, end, trackedIds)
            prefs.snapshot = Allowance.compute(today, prefs.startDay, budget, spent)
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
