package app.lunchwidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptParserTest {

    @Test
    fun parsesLabelAndAmount() {
        val items = ReceiptParser.parse(listOf("Butter Naan 45.00"))
        assertEquals(1, items.size)
        assertEquals(45.0, items[0].amount, 0.0)
        assertEquals("Butter Naan", items[0].label)
    }

    @Test
    fun stripsCurrencyCommasAndLeaders() {
        val items = ReceiptParser.parse(
            listOf(
                "Paneer Tikka ...... ₹1,240.50",
                "Dal Makhani - - - Rs 260",
                "Jeera Rice   INR 180.00",
            )
        )
        assertEquals(listOf(1240.5, 260.0, 180.0), items.map { it.amount })
        assertEquals(listOf("Paneer Tikka", "Dal Makhani", "Jeera Rice"), items.map { it.label })
    }

    @Test
    fun keepsQtyTextInLabel() {
        val items = ReceiptParser.parse(listOf("2 x Masala Dosa 240.00"))
        assertEquals(1, items.size)
        assertEquals(240.0, items[0].amount, 0.0)
        assertEquals("2 x Masala Dosa", items[0].label)
    }

    @Test
    fun skipsSummaryAndTaxLines() {
        val items = ReceiptParser.parse(
            listOf(
                "Veg Biryani 320.00",
                "Sub Total 320.00",
                "CGST 2.5% 8.00",
                "SGST 2.5% 8.00",
                "Service Charge 32.00",
                "Round Off 0.00",
                "Grand Total 368.00",
                "Cash 400.00",
                "Change Due 32.00",
            )
        )
        assertEquals(1, items.size)
        assertEquals("Veg Biryani", items[0].label)
    }

    @Test
    fun summaryWordsOnlyMatchWholeWords() {
        // "Subway", "Totapuri" must not trip the sub/total keyword filter.
        val items = ReceiptParser.parse(
            listOf("Subway Melt 250.00", "Totapuri Juice 90.00")
        )
        assertEquals(2, items.size)
    }

    @Test
    fun skipsLinesWithoutUsableAmount() {
        val items = ReceiptParser.parse(
            listOf(
                "HOTEL SARAVANA BHAVAN",
                "GSTIN 33AAACD1234F1Z5",
                "--------------------",
                "Idli 60.00",
                "Thanks! Visit again",
            )
        )
        assertEquals(1, items.size)
        assertEquals("Idli", items[0].label)
    }

    @Test
    fun rejectsOutOfRangeAmounts() {
        val items = ReceiptParser.parse(
            listOf(
                "Phone 9876543210",   // phone number, way out of range
                "Coffee 0",           // zero
                "Filter Coffee 40",
            )
        )
        assertEquals(1, items.size)
        assertEquals("Filter Coffee", items[0].label)
    }

    @Test
    fun allowsEmptyLabel() {
        val items = ReceiptParser.parse(listOf("85.00"))
        assertEquals(1, items.size)
        assertEquals(85.0, items[0].amount, 0.0)
        assertEquals("", items[0].label)
    }

    @Test
    fun emptyInputParsesToNothing() {
        assertTrue(ReceiptParser.parse(emptyList()).isEmpty())
    }
}
