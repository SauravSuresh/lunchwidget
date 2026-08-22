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
    fun bareNumberRowsAreNoise() {
        // Field data (supermarket GST rows): letterless rows are column
        // fragments or tax tables, never items.
        assertTrue(ReceiptParser.parse(listOf("85.00")).isEmpty())
        assertTrue(
            ReceiptParser.parse(
                listOf(
                    "18.00 (&) 140.80 25.30 12.66 12.66",
                    "6.00 (#) 1975.06 98.76 49.40 49.40",
                )
            ).isEmpty()
        )
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

    // --- regressions from the second real receipt (supermarket GST, 2026-08-22):
    // two-line items — name row, then a gst%/hsn/mrp/rate/qty/total numbers row.

    @Test
    fun pairsNameRowWithFollowingNumbersRow() {
        val items = ReceiptParser.parse(
            listOf(
                "3 REBOUND DETOX SHOT MANGO 60ML",
                "5 % 21069099 100.00 97.50 4.000 390.00",
            )
        )
        assertEquals(1, items.size)
        assertEquals(390.0, items[0].amount, 0.0)
        assertEquals("REBOUND DETOX SHOT MANGO 60ML", items[0].label)
    }

    @Test
    fun pairedAmountBeatsPackSizeOcrdAsNumber() {
        // "210ML 15S" OCRs as "...158"; the real total is on the numbers row.
        val items = ReceiptParser.parse(
            listOf(
                "7 UAJJAYINI RIPPLE CUP 210ML 158",
                "18% 48234000 86.00 83.54 1.000 83.54",
            )
        )
        assertEquals(1, items.size)
        assertEquals(83.54, items[0].amount, 0.0)
        assertEquals("UAJJAYINI RIPPLE CUP 210ML", items[0].label)
    }

    @Test
    fun skipsSavedLine() {
        assertTrue(ReceiptParser.parse(listOf("You have sAved 79.22")).isEmpty())
    }

    @Test
    fun rateTimesQtyCorrectsMisreadTotal() {
        // Third field test: OCR read 572.16 as 672.16; rate × qty knows better.
        val items = ReceiptParser.parse(
            listOf(
                "6 REBOUND DETOX SHOT LIME 60ML",
                "5 % 21069099 100.00 95.36 6.000 672.16",
            )
        )
        assertEquals(1, items.size)
        assertEquals(572.16, items[0].amount, 0.001)
    }

    @Test
    fun consistentTotalIsKeptAsIs() {
        val items = ReceiptParser.parse(
            listOf(
                "3 REBOUND DETOX SHOT MANGO 60ML",
                "5 % 21069099 100.00 97.50 4.000 390.00",
            )
        )
        assertEquals(390.0, items[0].amount, 0.001)
    }

    @Test
    fun pairsAcrossSeparatorRow() {
        // Item 11 borders the totals block; a dashed rule OCRs in between.
        val items = ReceiptParser.parse(
            listOf(
                "11 UAJJAYINI RIPPLE CUP 210ML 108",
                "------------------------",
                "18% 48234000 58.00 56.34 1.000 56.34",
            )
        )
        assertEquals(1, items.size)
        assertEquals(56.34, items[0].amount, 0.001)
        assertEquals("UAJJAYINI RIPPLE CUP 210ML", items[0].label)
    }

    @Test
    fun supermarketReceiptEndToEnd() {
        val items = ReceiptParser.parse(
            listOf(
                "GST: 32ADGPG9983P1ZT",
                "Bill B-26/-107392 Date 22/08/2026 11:42",
                "Item Description",
                "Gst% HsnCode MRP Rate Qty Total",
                "1 CARRY BAGS MED",
                "18% 63053300 8.00 8.00 1.000 8.00",
                "2 KC CELLO TAPE CLEAR 12*50MM",
                "18% 39191000 19.00 18.04 1.000 18.04",
                "6 REBOUND DETOX SHOT LIME 60ML",
                "5 % 21069099 100.00 95.36 6.000 572.16",
                "11 UAJJAYINI RIPPLE CUP 210ML 108",
                "18% 48234000 58.00 56.34 1.000 56.34",
                "ItemTotal Amt 2319.00",
                "You have saved 79.22",
                "Round off : 0.00",
                "Grand Total 2239.78",
                "18.00 (&) 140.80 25.30 12.66 12.66",
                "6.00 (#) 1975.06 98.76 49.40 49.40",
                "Tender Cash 2239.78",
                "No. of items: 11",
                "TotalPoints : 208.00",
            )
        )
        assertEquals(listOf(8.0, 18.04, 572.16, 56.34), items.map { it.amount })
        assertEquals(
            listOf(
                "CARRY BAGS MED",
                "KC CELLO TAPE CLEAR 12*50MM",
                "REBOUND DETOX SHOT LIME 60ML",
                "UAJJAYINI RIPPLE CUP 210ML",
            ),
            items.map { it.label }
        )
    }
}
