package app.lunchwidget

import org.junit.Assert.assertEquals
import org.junit.Test

class SplitMathTest {

    private fun shares(vararg s: Share) = s.toList()

    @Test
    fun slugifies() {
        assertEquals("alex-p", SplitMath.slugify("  Alex  P "))
        assertEquals("blake", SplitMath.slugify("Blake"))
    }

    @Test
    fun equalSplitRemainderToFirstUnlocked() {
        val s = shares(
            Share("me", "You", true),
            Share("alex", "Alex", false),
            Share("blake", "Blake", false),
        )
        val off = SplitMath.rebalance(1000.0, s)
        assertEquals(0.0, off, 0.0)
        assertEquals(333.34, s[0].amount, 0.0)
        assertEquals(333.33, s[1].amount, 0.0)
        assertEquals(333.33, s[2].amount, 0.0)
        assertEquals(1000.0, s.sumOf { it.amount }, 1e-9)
    }

    @Test
    fun lockedRowKeepsAmountOthersRebalance() {
        val s = shares(
            Share("me", "You", true),
            Share("alex", "Alex", false, locked = true, amount = 600.0),
            Share("blake", "Blake", false),
        )
        val off = SplitMath.rebalance(1000.0, s)
        assertEquals(0.0, off, 0.0)
        assertEquals(600.0, s[1].amount, 0.0)
        assertEquals(200.0, s[0].amount, 0.0)
        assertEquals(200.0, s[2].amount, 0.0)
    }

    @Test
    fun allLockedBadSumReportsOffBy() {
        val s = shares(
            Share("me", "You", true, locked = true, amount = 500.0),
            Share("alex", "Alex", false, locked = true, amount = 300.0),
        )
        val off = SplitMath.rebalance(1000.0, s)
        assertEquals(200.0, off, 0.0)
    }

    @Test
    fun pourFillsOldestFirstWithPartialBoundary() {
        val items = listOf(
            OwedItem("2026-07-12", "Dinner", 413.33),
            OwedItem("2026-07-19", "Movie", 620.0),
            OwedItem("2026-08-02", "Chai", 250.0),
        )
        val poured = SplitMath.pour(items, 500.0)
        assertEquals(413.33, poured[0].take, 0.0)
        assertEquals(86.67, poured[1].take, 0.0)
        assertEquals(0.0, poured[2].take, 0.0)
    }

    @Test
    fun groupPendingNetsRepaymentsAndKeepsRemainders() {
        val txns = listOf(
            PendingTxn("2026-07-12", "Dinner", 413.33, listOf("owed:alex")),
            PendingTxn("2026-07-19", "Movie", 620.0, listOf("owed:alex")),
            PendingTxn("2026-08-01", "Repayment", -500.0, listOf("owed:alex")),
            PendingTxn("2026-08-05", "Groceries", 512.5, listOf("owed:blake")),
            PendingTxn("2026-08-06", "Lunch", 100.0, listOf("unrelated-tag")),
        )
        val pending = SplitMath.groupPending(txns, "owed:").associateBy { it.slug }
        assertEquals(setOf("alex", "blake"), pending.keys)

        val alex = pending.getValue("alex")
        assertEquals(533.33, alex.total, 0.0)
        // 500 poured over the 413.33 dinner leaves 86.67 into the movie: one item remains.
        assertEquals(1, alex.items.size)
        assertEquals(533.33, alex.items[0].amount, 0.0)
        assertEquals("Movie", alex.items[0].payee)

        assertEquals(512.5, pending.getValue("blake").total, 0.0)
    }

    @Test
    fun groupPendingOverpaidGoesNegativeWithNoItems() {
        val txns = listOf(
            PendingTxn("2026-07-12", "Dinner", 400.0, listOf("owed:devon")),
            PendingTxn("2026-08-01", "Repayment", -450.0, listOf("owed:devon")),
        )
        val devon = SplitMath.groupPending(txns, "owed:").single()
        assertEquals(-50.0, devon.total, 0.0)
        assertEquals(0, devon.items.size)
    }

    @Test
    fun pendingJsonRoundTrips() {
        val pending = listOf(
            PendingPerson("alex", 533.33, listOf(OwedItem("2026-07-19", "Movie", 533.33))),
            PendingPerson("devon", -50.0, emptyList()),
        )
        assertEquals(pending, SplitMath.pendingFromJson(SplitMath.pendingToJson(pending)))
    }
}
