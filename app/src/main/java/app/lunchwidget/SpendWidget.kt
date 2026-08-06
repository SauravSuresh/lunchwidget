package app.lunchwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

class SpendWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) mgr.updateAppWidget(id, render(context))
        RefreshWorker.refreshNow(context)
    }

    override fun onEnabled(context: Context) {
        RefreshWorker.schedulePeriodic(context)
        RefreshWorker.refreshNow(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) RefreshWorker.refreshNow(context)
    }

    companion object {
        const val ACTION_REFRESH = "app.lunchwidget.REFRESH"

        fun updateAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, SpendWidget::class.java))
            for (id in ids) mgr.updateAppWidget(id, render(context))
        }

        private fun render(context: Context): RemoteViews {
            val prefs = Prefs(context)
            val views = RemoteViews(context.packageName, R.layout.widget)
            val snap = prefs.snapshot

            if (prefs.token.isBlank() || snap == null) {
                views.setTextViewText(R.id.allowance, context.getString(R.string.setup))
                views.setTextViewText(R.id.subline, context.getString(R.string.setup_hint))
                views.setOnClickPendingIntent(
                    R.id.widget_root,
                    activityIntent(context, SettingsActivity::class.java, 0)
                )
                return views
            }

            val sym = prefs.currency
            val overToday = snap.leftToday < 0
            views.setTextViewText(R.id.allowance, Allowance.fmt(snap.leftToday, sym))
            // Red is an interrupt, not decoration: only when today's limit is blown.
            views.setTextColor(R.id.allowance, if (overToday) ACCENT else 0xFFFFFFFF.toInt())
            views.setImageViewBitmap(R.id.bar, segmentsBitmap(snap.progressToday))
            val pace = context.getString(if (snap.onTrack) R.string.on_track else R.string.over_pace)
            val stale = if (prefs.lastError) " · STALE" else ""
            views.setTextViewText(
                R.id.subline,
                ("${Allowance.fmt(snap.spentToday, sym)}/${Allowance.fmt(snap.allowanceToday, sym)} today · " +
                    "${Allowance.fmt(snap.remaining, sym)} · ${snap.daysLeft}d · $pace$stale").uppercase()
            )
            views.setOnClickPendingIntent(
                R.id.widget_root,
                activityIntent(context, QuickAddActivity::class.java, 1)
            )
            val refresh = Intent(context, SpendWidget::class.java).setAction(ACTION_REFRESH)
            views.setOnClickPendingIntent(
                R.id.refresh,
                PendingIntent.getBroadcast(
                    context, 2, refresh,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            return views
        }

        private const val ACCENT = 0xFFD71921.toInt()

        // Nothing-style segmented bar: discrete square blocks, white = spent within
        // today's allowance, red = overflow past the limit, grey = still available.
        private fun segmentsBitmap(progress: Double): android.graphics.Bitmap {
            val n = 24
            val segW = 20
            val gap = 5
            val h = 24
            val bmp = android.graphics.Bitmap.createBitmap(
                n * segW + (n - 1) * gap, h, android.graphics.Bitmap.Config.ARGB_8888
            )
            val canvas = android.graphics.Canvas(bmp)
            val paint = android.graphics.Paint()
            val p = maxOf(0.0, progress)
            val filled: Int
            val red: Int
            if (p <= 1.0) {
                filled = Math.round(n * p).toInt()
                red = 0
            } else {
                filled = n
                red = n - Math.max(1, Math.round(n / p).toInt())
            }
            for (i in 0 until n) {
                paint.color = when {
                    i >= filled -> 0xFF262626.toInt()
                    i >= n - red -> ACCENT
                    else -> 0xFFFFFFFF.toInt()
                }
                val x = i * (segW + gap).toFloat()
                canvas.drawRect(x, 0f, x + segW, h.toFloat(), paint)
            }
            return bmp
        }

        private fun activityIntent(context: Context, cls: Class<*>, req: Int): PendingIntent =
            PendingIntent.getActivity(
                context, req, Intent(context, cls),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
    }
}
