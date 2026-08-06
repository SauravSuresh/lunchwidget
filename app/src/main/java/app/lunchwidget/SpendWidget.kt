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
            val stale = if (prefs.lastError) " !" else ""
            views.setTextViewText(
                R.id.allowance,
                context.getString(R.string.allowance_line, Allowance.fmt(snap.allowance, sym)) + stale
            )
            val pace = if (snap.onTrack) context.getString(R.string.on_track)
            else context.getString(R.string.over_pace)
            views.setTextViewText(
                R.id.subline,
                "${Allowance.fmt(snap.remaining, sym)} left · ${snap.daysLeft}d · $pace"
            )
            views.setTextColor(
                R.id.subline,
                if (snap.onTrack) 0xFF7BD98F.toInt() else 0xFFFF8A80.toInt()
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

        private fun activityIntent(context: Context, cls: Class<*>, req: Int): PendingIntent =
            PendingIntent.getActivity(
                context, req, Intent(context, cls),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
    }
}
