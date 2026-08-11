package app.lunchwidget

import org.junit.Assert.assertEquals
import java.time.LocalDate
import org.junit.Test

/**
 * The queued write is only as good as what survives the trip through WorkManager's
 * Data bundle. A field silently dropped here is a transaction that posts with the
 * wrong amount, the wrong account, or no tag to settle against later.
 */
class PostWorkerCodecTest {

    @Test
    fun roundTripsEveryField() {
        // Ids and names here are invented — this repo is public, so fixtures never
        // carry real category ids, asset ids, or anyone's name.
        val txns = listOf(
            // A backdated split share: note, no tags, an ordinary category, an account.
            NewTxn(LocalDate.of(2026, 8, 3), 249.5, 1001L, "dinner", assetId = 2002L),
            // An owed portion: placeholder category the worker resolves, tagged.
            NewTxn(
                LocalDate.of(2026, 8, 3), 500.0, PostWorker.REIMBURSEMENTS, "dinner",
                tags = listOf("owed:example-person"), assetId = 2002L,
            ),
            // A repayment: negative, no note, no account.
            NewTxn(LocalDate.of(2026, 8, 11), -500.0, PostWorker.REIMBURSEMENTS, null),
        )
        assertEquals(txns, PostWorker.decode(PostWorker.encode(txns)))
    }

    @Test
    fun blankNoteDecodesAsNullNotEmptyString() {
        // insertTransactions turns a null/blank note into the "Quick add" payee, so
        // "" and null have to stay interchangeable across the round trip.
        val decoded = PostWorker.decode(
            PostWorker.encode(listOf(NewTxn(LocalDate.of(2026, 8, 11), 40.0, 1L, "")))
        )
        assertEquals(null, decoded.single().note)
    }
}
