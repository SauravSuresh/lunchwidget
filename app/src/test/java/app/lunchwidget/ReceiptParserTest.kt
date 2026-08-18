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

    // --- regressions from the first real receipt (Chopstix, 2026-08-18) ---

    @Test
    fun skipsColonKeyValueLines() {
        // "21:47" became item "21" @ 47; "Dine In: 4" became an item @ 4.
        val items = ReceiptParser.parse(
            listOf("21:47", "Dine In: 4", "Kombucha 149.00")
        )
        assertEquals(1, items.size)
        assertEquals("Kombucha", items[0].label)
    }

    @Test
    fun stripsQtyAndPriceColumnsFromLabel() {
        // Receipt rows carry qty and unit-price columns before the amount.
        val items = ReceiptParser.parse(
            listOf(
                "Signature Miso 1 530.00 530.00",
                "Kombucha 1 149.00 149.00",
            )
        )
        assertEquals(listOf(530.0, 149.0), items.map { it.amount })
        assertEquals(listOf("Signature Miso", "Kombucha"), items.map { it.label })
    }

    @Test
    fun rowsJoinsColumnsByVerticalOverlap() {
        // ML Kit split the name column and price columns into separate lines;
        // same receipt row = same vertical band, ordered left to right.
        val rows = ReceiptParser.rows(
            listOf(
                ReceiptParser.OcrLine("Signature Miso", 100, 130, 10),
                ReceiptParser.OcrLine("Kombucha", 200, 230, 10),
                ReceiptParser.OcrLine("1 530.00 530.00", 102, 131, 300),
                ReceiptParser.OcrLine("1 149.00 149.00", 201, 232, 300),
            )
        )
        assertEquals(
            listOf("Signature Miso 1 530.00 530.00", "Kombucha 1 149.00 149.00"),
            rows
        )
    }

    @Test
    fun chopstixReceiptEndToEnd() {
        val rows = ReceiptParser.rows(
            listOf(
                ReceiptParser.OcrLine("Date: 14/08/26", 10, 40, 10),
                ReceiptParser.OcrLine("Dine In: 4", 12, 41, 300),
                ReceiptParser.OcrLine("21:47", 50, 80, 10),
                ReceiptParser.OcrLine("Item", 90, 120, 10),
                ReceiptParser.OcrLine("Qty. Price Amount", 91, 121, 200),
                ReceiptParser.OcrLine("Signature Miso", 130, 160, 10),
                ReceiptParser.OcrLine("1 530.00 530.00", 131, 161, 300),
                ReceiptParser.OcrLine("Ramen Chciken", 165, 195, 10),
                ReceiptParser.OcrLine("Singapore Prawns", 210, 240, 10),
                ReceiptParser.OcrLine("1 650.00 650.00", 211, 241, 300),
                ReceiptParser.OcrLine("Served With 150g", 245, 275, 10),
                ReceiptParser.OcrLine("Jasmine Rice", 280, 310, 10),
                ReceiptParser.OcrLine("Kombucha", 320, 350, 10),
                ReceiptParser.OcrLine("1 149.00 149.00", 321, 351, 300),
                ReceiptParser.OcrLine("Total Qty: 3", 360, 390, 100),
                ReceiptParser.OcrLine("Sub Total 1329.00", 361, 391, 300),
                ReceiptParser.OcrLine("SGST 2.5% 29.50", 400, 430, 200),
                ReceiptParser.OcrLine("CGST 2.5% 29.50", 435, 465, 200),
                ReceiptParser.OcrLine("Grand Total ₹1388.00", 470, 500, 100),
            )
        )
        val items = ReceiptParser.parse(rows)
        assertEquals(listOf(530.0, 650.0, 149.0), items.map { it.amount })
        assertEquals(
            listOf("Signature Miso", "Singapore Prawns", "Kombucha"),
            items.map { it.label }
        )
    }
}
