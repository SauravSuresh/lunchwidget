package app.lunchwidget

import java.time.LocalDate
import java.time.YearMonth

data class Category(val id: Long, val name: String, val groupId: Long?, val isGroup: Boolean)

data class Snapshot(
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val totalBudget: Double,
    val spent: Double,
    val remaining: Double,
    val daysLeft: Int,
    val allowance: Double,
    val paceDelta: Double,
    val onTrack: Boolean,
)

// Pure port of dailyspend's compute.py — same math, testable on the JVM.
object Allowance {

    fun period(today: LocalDate, startDay: Int): Pair<LocalDate, LocalDate> {
        if (startDay == 1) {
            val ym = YearMonth.from(today)
            return ym.atDay(1) to ym.atEndOfMonth()
        }
        val thisMonthStart = clamp(YearMonth.from(today), startDay)
        val start = if (today >= thisMonthStart) thisMonthStart
        else clamp(YearMonth.from(today).minusMonths(1), startDay)
        val nextStart = clamp(YearMonth.from(start).plusMonths(1), startDay)
        return start to nextStart.minusDays(1)
    }

    private fun clamp(ym: YearMonth, day: Int): LocalDate = ym.atDay(minOf(day, ym.lengthOfMonth()))

    // Tracked names expand to include children of a tracked group category.
    fun trackedIds(categories: List<Category>, trackedNames: List<String>): Set<Long> {
        val byName = categories.associateBy { it.name }
        val children = categories.filter { it.groupId != null }
            .groupBy({ it.groupId!! }, { it.id })
        val ids = mutableSetOf<Long>()
        for (name in trackedNames) {
            val cat = byName[name] ?: continue
            ids.add(cat.id)
            children[cat.id]?.let { ids.addAll(it) }
        }
        return ids
    }

    fun compute(today: LocalDate, startDay: Int, totalBudget: Double, spent: Double): Snapshot {
        val (start, end) = period(today, startDay)
        val remaining = totalBudget - spent
        val daysLeft = maxOf(1, (end.toEpochDay() - today.toEpochDay()).toInt())
        val totalDays = (end.toEpochDay() - start.toEpochDay() + 1).toInt()
        val elapsed = (today.toEpochDay() - start.toEpochDay() + 1).toInt()
        val expected = totalBudget * elapsed.toDouble() / totalDays
        val pace = expected - spent
        return Snapshot(
            periodStart = start,
            periodEnd = end,
            totalBudget = totalBudget,
            spent = spent,
            remaining = remaining,
            daysLeft = daysLeft,
            allowance = remaining / daysLeft,
            paceDelta = pace,
            onTrack = pace >= 0,
        )
    }

    // Indian-style digit grouping, no decimals: 123456 -> ₹1,23,456
    fun fmt(n: Double, symbol: String): String {
        val v = Math.round(n)
        var s = Math.abs(v).toString()
        if (s.length > 3) {
            val last3 = s.takeLast(3)
            var rest = s.dropLast(3)
            val parts = mutableListOf<String>()
            while (rest.length > 2) {
                parts.add(0, rest.takeLast(2))
                rest = rest.dropLast(2)
            }
            if (rest.isNotEmpty()) parts.add(0, rest)
            s = parts.joinToString(",") + "," + last3
        }
        return (if (v < 0) "-" else "") + symbol + s
    }
}
