package app.lunchwidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AllowanceTest {

    @Test
    fun calendarMonthPeriod() {
        val (s, e) = Allowance.period(LocalDate.of(2026, 8, 6), 1)
        assertEquals(LocalDate.of(2026, 8, 1), s)
        assertEquals(LocalDate.of(2026, 8, 31), e)
    }

    @Test
    fun customPeriodAfterStartDay() {
        val (s, e) = Allowance.period(LocalDate.of(2026, 8, 30), 29)
        assertEquals(LocalDate.of(2026, 8, 29), s)
        assertEquals(LocalDate.of(2026, 9, 28), e)
    }

    @Test
    fun customPeriodBeforeStartDay() {
        val (s, e) = Allowance.period(LocalDate.of(2026, 8, 6), 29)
        assertEquals(LocalDate.of(2026, 7, 29), s)
        assertEquals(LocalDate.of(2026, 8, 28), e)
    }

    @Test
    fun februaryClampsStartDay() {
        // Feb 2026 has 28 days; day 29 clamps to Feb 28.
        val (s, e) = Allowance.period(LocalDate.of(2026, 2, 28), 29)
        assertEquals(LocalDate.of(2026, 2, 28), s)
        assertEquals(LocalDate.of(2026, 3, 28), e)
    }

    @Test
    fun allowanceIsRemainingOverDaysLeft() {
        // Aug 6, period Jul 29–Aug 28: 22 days left after today.
        val snap = Allowance.compute(LocalDate.of(2026, 8, 6), 29, 31000.0, 9000.0)
        assertEquals(22, snap.daysLeft)
        assertEquals(1000.0, snap.allowance, 0.01)
        assertEquals(22000.0, snap.remaining, 0.01)
    }

    @Test
    fun lastDayGuard() {
        val snap = Allowance.compute(LocalDate.of(2026, 8, 28), 29, 31000.0, 30000.0)
        assertEquals(1, snap.daysLeft)
        assertEquals(1000.0, snap.allowance, 0.01)
    }

    @Test
    fun paceFlags() {
        // Day 9 of 31, expected = 31000 * 9/31 = 9000
        val under = Allowance.compute(LocalDate.of(2026, 8, 6), 29, 31000.0, 8000.0)
        assertTrue(under.onTrack)
        val over = Allowance.compute(LocalDate.of(2026, 8, 6), 29, 31000.0, 10000.0)
        assertFalse(over.onTrack)
        assertEquals(-1000.0, over.paceDelta, 0.01)
    }

    @Test
    fun todayAllowanceIgnoresTodaySpend() {
        // Aug 6: 23 days incl today. Before today: 8000 spent of 31000 -> 1000/day.
        val snap = Allowance.compute(LocalDate.of(2026, 8, 6), 29, 31000.0, 8000.0, 400.0)
        assertEquals(1000.0, snap.allowanceToday, 0.01)
        assertEquals(600.0, snap.leftToday, 0.01)
        assertEquals(0.4, snap.progressToday, 0.001)
        assertEquals(8400.0, snap.spent, 0.01)
    }

    @Test
    fun overspendTodayGoesNegative() {
        val snap = Allowance.compute(LocalDate.of(2026, 8, 6), 29, 31000.0, 8000.0, 1500.0)
        assertEquals(-500.0, snap.leftToday, 0.01)
        assertEquals(1.5, snap.progressToday, 0.001)
    }

    @Test
    fun trackedGroupExpandsToChildren() {
        val cats = listOf(
            Category(1, "Living Expenses", null, true),
            Category(2, "Groceries", 1, false),
            Category(3, "Eating Out", 1, false),
            Category(4, "Rent", null, false),
        )
        assertEquals(setOf(1L, 2L, 3L), Allowance.trackedIds(cats, listOf("Living Expenses")))
        assertEquals(setOf(4L), Allowance.trackedIds(cats, listOf("Rent")))
        assertEquals(emptySet<Long>(), Allowance.trackedIds(cats, listOf("Nope")))
    }

    @Test
    fun indianGrouping() {
        assertEquals("₹362", Allowance.fmt(362.4, "₹"))
        assertEquals("₹1,23,456", Allowance.fmt(123456.0, "₹"))
        assertEquals("-₹12,34,567", Allowance.fmt(-1234567.0, "₹"))
        assertEquals("₹1,000", Allowance.fmt(999.6, "₹"))
    }
}
