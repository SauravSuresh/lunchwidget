package app.lunchwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
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

        // Nothing tokens: red fill for over-limit, brightened red for text on dark,
        // amber for the caution zone, greys from the token scale.
        private const val ACCENT = 0xFFD71921.toInt()
        private const val ACCENT_TEXT = 0xFFFF4438.toInt()
        private const val WARNING = 0xFFF2C94C.toInt()
        private const val DISPLAY = 0xFFFFFFFF.toInt()
        private const val EMPTY = 0xFF262626.toInt()
        private const val WARN_AT = 0.7

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
                views.setImageViewBitmap(
                    R.id.allowance, heroBitmap(context, context.getString(R.string.setup), DISPLAY)
                )
                views.setTextViewText(R.id.subline, context.getString(R.string.setup_hint))
                views.setOnClickPendingIntent(
                    R.id.widget_root,
                    activityIntent(context, SettingsActivity::class.java, 0)
                )
                return views
            }

            val sym = prefs.currency
            val p = snap.progressToday
            // Status color on the value: neutral -> amber caution -> red over limit.
            val stateColor = when {
                p > 1.0 -> ACCENT_TEXT
                p >= WARN_AT -> WARNING
                else -> DISPLAY
            }
            views.setImageViewBitmap(
                R.id.allowance,
                heroBitmap(context, Allowance.fmt(snap.leftToday, sym), stateColor)
            )
            views.setImageViewBitmap(R.id.bar, segmentsBitmap(p))
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

        // Segmented bar: discrete blocks. Fill color tracks the same state ramp as the
        // hero number; overflow segments past the limit mark are always signal red.
        private fun segmentsBitmap(progress: Double): Bitmap {
            val n = 24
            val segW = 20
            val gap = 5
            val h = 24
            val bmp = Bitmap.createBitmap(n * segW + (n - 1) * gap, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val paint = Paint()
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
            val fillColor = if (p >= WARN_AT) WARNING else DISPLAY
            for (i in 0 until n) {
                paint.color = when {
                    i >= filled -> EMPTY
                    i >= n - red -> ACCENT
                    else -> fillColor
                }
                val x = i * (segW + gap).toFloat()
                canvas.drawRect(x, 0f, x + segW, h.toFloat(), paint)
            }
            return bmp
        }

        // Hero line as a bitmap: Doto (round dots) for everything it covers, and a
        // hand-stamped dot-matrix ₹ since Doto has no rupee glyph.
        private fun heroBitmap(context: Context, text: String, color: Int): Bitmap {
            val size = 120f
            val doto = context.resources.getFont(R.font.doto)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = doto
                fontVariationSettings = "'ROND' 100, 'wght' 700"
                textSize = size
                this.color = color
            }
            val rupeeH = size * 0.68f
            val rupeeCell = rupeeH / RUPEE_ROWS
            val rupeeW = rupeeCell * RUPEE_COLS
            var w = 8f
            for (ch in text) {
                w += if (ch == '₹') rupeeW + size * 0.08f else paint.measureText(ch.toString())
            }
            val fm = paint.fontMetrics
            val hgt = -fm.ascent + fm.descent
            val bmp = Bitmap.createBitmap(w.toInt() + 8, hgt.toInt() + 4, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val baseline = -fm.ascent + 2f
            var x = 4f
            for (ch in text) {
                if (ch == '₹') {
                    drawDottedRupee(canvas, x, baseline, rupeeCell, paint)
                    x += rupeeW + size * 0.08f
                } else {
                    canvas.drawText(ch.toString(), x, baseline, paint)
                    x += paint.measureText(ch.toString())
                }
            }
            return bmp
        }

        // ₹ on a 7x9 grid of full circles, matching Doto's round-dot voice.
        private const val RUPEE_COLS = 7
        private const val RUPEE_ROWS = 9
        private val RUPEE_GRID = arrayOf(
            "1111111",
            "0000011",
            "1111111",
            "0000011",
            "0000110",
            "0001100",
            "0011000",
            "0110000",
            "1100000",
        )

        private fun drawDottedRupee(canvas: Canvas, x: Float, baseline: Float, cell: Float, paint: Paint) {
            val top = baseline - cell * RUPEE_ROWS
            val r = cell * 0.42f
            for (row in 0 until RUPEE_ROWS) {
                for (col in 0 until RUPEE_COLS) {
                    if (RUPEE_GRID[row][col] == '1') {
                        canvas.drawCircle(
                            x + (col + 0.5f) * cell,
                            top + (row + 0.5f) * cell,
                            r,
                            paint
                        )
                    }
                }
            }
        }

        private fun activityIntent(context: Context, cls: Class<*>, req: Int): PendingIntent =
            PendingIntent.getActivity(
                context, req, Intent(context, cls),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
    }
}
