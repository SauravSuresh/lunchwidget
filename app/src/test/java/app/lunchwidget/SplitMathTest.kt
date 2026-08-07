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
    fun itemizeScalesTaxProportionally() {
        // Items sum 240, bill 270 → 12.5% fees prorated: exactly GST-style.
        val items = listOf(
            BillItem(160.0, "biryani", setOf("")),
            BillItem(80.0, "dosa", setOf("casey")),
        )
        val shares = SplitMath.itemize(270.0, items, "")
        assertEquals(180.0, shares.getValue(""), 0.0)
        assertEquals(90.0, shares.getValue("casey"), 0.0)
    }

    @Test
    fun itemizeSharedItemSplitsAmongAssignees() {
        val items = listOf(
            BillItem(90.0, "starter", setOf("", "casey", "devon")),
            BillItem(100.0, "mains", setOf("casey")),
        )
        val shares = SplitMath.itemize(190.0, items, "")
        assertEquals(30.0, shares.getValue(""), 0.0)
        assertEquals(130.0, shares.getValue("casey"), 0.0)
        assertEquals(30.0, shares.getValue("devon"), 0.0)
    }

    @Test
    fun itemizeDiscountScalesDownAndRemainderToMe() {
        // Bill 100 vs items 150 (coupon): scale 2/3; odd thirds round, diff lands on me.
        val items = listOf(
            BillItem(50.0, "", setOf("")),
            BillItem(50.0, "", setOf("casey")),
            BillItem(50.0, "", setOf("devon")),
        )
        val shares = SplitMath.itemize(100.0, items, "")
        assertEquals(100.0, shares.values.sum(), 1e-9)
        assertEquals(33.33, shares.getValue("casey"), 0.0)
        assertEquals(33.33, shares.getValue("devon"), 0.0)
        assertEquals(33.34, shares.getValue(""), 0.0)
    }

    @Test
    fun itemizeIgnoresUnassignedAndEmpty() {
        assertEquals(emptyMap<String, Double>(), SplitMath.itemize(100.0, emptyList(), ""))
        assertEquals(
            emptyMap<String, Double>(),
            SplitMath.itemize(100.0, listOf(BillItem(50.0, "", emptySet())), "")
        )
    }

    @Test
    fun initialsExtendOnCollision() {
        val ini = SplitMath.uniqueInitials(
            listOf("" to "You", "ada" to "Ada", "alex" to "Alex", "casey-blake" to "Casey Blake")
        )
        assertEquals("Y", ini[""])
        assertEquals("AD", ini["ada"])
        assertEquals("AL", ini["alex"])
        assertEquals("CB", ini["casey-blake"])
        assertEquals(ini.size, ini.values.toSet().size)
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
