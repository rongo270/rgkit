package dev.rgkit.screenshotiq

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Drives [ScreenshotIQ.classify] with realistic OCR text — the same shape ML
 * Kit hands back — so the heuristics, entity extraction and suggestions are
 * checked without a device, an image or a Uri.
 */
class ScreenshotIQTests {

    @Before
    fun setUp() {
        ScreenshotIQ.config = ScreenshotConfig()
        ScreenshotIQ.reset()
    }

    @After
    fun tearDown() {
        ScreenshotIQ.reset()
        ScreenshotIQ.config = ScreenshotConfig()
    }

    private fun classify(text: String, blocks: Int = 6) =
        ScreenshotIQ.classify(text.trimIndent(), blocks, null)

    // ------------------------------------------------------------ the kinds

    @Test
    fun aTillReceiptIsARecipt() {
        val insight = classify(
            """
            WHOLE FOODS MARKET
            Order #4821
            Subtotal   ${'$'}38.40
            Tax         ${'$'}4.50
            Total      ${'$'}42.90
            Paid with VISA
            """
        )
        assertEquals(ScreenshotKind.RECEIPT, insight.kind)
        assertEquals("${'$'}42.90", insight.entities["total"])
        assertEquals(
            listOf("save_expense", "extract_total"),
            insight.suggestions.map { it.id },
        )
        assertTrue(insight.suggestions[1].label, insight.suggestions[1].label.contains("${'$'}42.90"))
    }

    @Test
    fun aShoppingPageIsAProduct() {
        val insight = classify(
            """
            Sony WH-1000XM5
            ${'$'}349.99
            ★★★★☆ 1,248 reviews
            In stock — Free shipping
            Add to cart
            Buy now
            """
        )
        assertEquals(ScreenshotKind.PRODUCT, insight.kind)
        assertEquals(listOf("save_wishlist", "price_watch"), insight.suggestions.map { it.id })
    }

    @Test
    fun aCrashDialogIsAnError() {
        val insight = classify(
            """
            Something went wrong
            Error: Unable to load your data
            java.net.SocketTimeoutException: timeout
            at kotlin.coroutines.ContinuationImpl.resume
            Try again
            """
        )
        assertEquals(ScreenshotKind.ERROR, insight.kind)
        assertEquals("Error: Unable to load your data", insight.entities["error_line"])
        assertEquals(listOf("report_bug", "search_help"), insight.suggestions.map { it.id })
    }

    @Test
    fun anHttpStatusOnItsOwnStillReadsAsAnError() {
        val insight = classify(
            """
            HTTP 404
            The page you were looking for is gone
            """
        )
        assertEquals(ScreenshotKind.ERROR, insight.kind)
    }

    @Test
    fun digitsInsideLongerNumbersAreNotStatusCodes() {
        // "1500" and "word404" used to score as 403/404/500 hits and pushed
        // ordinary screenshots into ERROR.
        val prose = (1..300).joinToString(" ") { "lorem ipsum dolor sit amet 404th 1500 word403" }
        assertEquals(ScreenshotKind.DOCUMENT, classify(prose).kind)

        val receipt = classify(
            """
            Subtotal   1500.00 ILS
            Tax         255.00 ILS
            Total      1755.00 ILS
            Receipt #4040 paid
            """
        )
        assertEquals(ScreenshotKind.RECEIPT, receipt.kind)
    }

    @Test
    fun aMessageThreadIsAChat() {
        val insight = classify(
            """
            Maya
            online
            Hey! are you coming? 18:20
            yeah leaving now 18:21
            cool see you there 18:21
            delivered
            """
        )
        assertEquals(ScreenshotKind.CHAT, insight.kind)
        assertEquals(listOf("save_note"), insight.suggestions.map { it.id })
    }

    @Test
    fun aBoardingPassIsATicket() {
        val insight = classify(
            """
            Boarding Pass
            Flight LY315
            Gate B12  Seat 24A
            Confirmation ABC123
            Departure Mar 14  10:45
            """
        )
        assertEquals(ScreenshotKind.TICKET, insight.kind)
        assertEquals("ABC123", insight.entities["code"])
        assertEquals("Mar 14", insight.entities["date"])
        assertEquals(listOf("add_calendar", "save_ticket"), insight.suggestions.map { it.id })
    }

    @Test
    fun aNavigationScreenIsAMap() {
        val insight = classify(
            """
            12 min (4.5 km)
            Fastest route now
            via Ayalon Hwy
            """,
            blocks = 3,
        )
        assertEquals(ScreenshotKind.MAP, insight.kind)
        assertEquals(listOf("open_maps"), insight.suggestions.map { it.id })
    }

    @Test
    fun sourceOnAScreenIsCode() {
        val insight = classify(
            """
            fun main() {
                val list = listOf(1, 2, 3)
                if (list.size == 3) { return }
            }
            import kotlin.math.min
            """
        )
        assertEquals(ScreenshotKind.CODE, insight.kind)
        assertEquals(listOf("share_snippet"), insight.suggestions.map { it.id })
    }

    @Test
    fun aFeedPostIsSocial() {
        val insight = classify(
            """
            1,248 likes
            312 comments
            Share
            Follow
            2.1M views
            """
        )
        assertEquals(ScreenshotKind.SOCIAL, insight.kind)
        assertEquals(listOf("save_bookmark"), insight.suggestions.map { it.id })
    }

    @Test
    fun wallsOfProseAreDocuments() {
        val prose = (1..300).joinToString(" ") { "lorem ipsum dolor sit amet" }
        val insight = classify(prose)

        assertEquals(ScreenshotKind.DOCUMENT, insight.kind)
        assertEquals(listOf("save_pdf"), insight.suggestions.map { it.id })
    }

    @Test
    fun whatItCannotPlaceStaysOther() {
        val insight = classify("hello there")

        assertEquals(ScreenshotKind.OTHER, insight.kind)
        assertEquals(0.3, insight.confidence, 1e-9)
        assertEquals(listOf("share"), insight.suggestions.map { it.id })
    }

    @Test
    fun anEmptyReadIsStillAnAnswer() {
        val insight = ScreenshotIQ.classify("", 0, null)

        assertEquals(ScreenshotKind.OTHER, insight.kind)
        assertTrue(insight.analyzed)
        assertTrue(insight.entities.isEmpty())
        assertEquals("", insight.textSample)
    }

    // --------------------------------------------------------- the entities

    @Test
    fun theTotalIsTakenFromTheTotalLineNotJustTheLastPrice() {
        val insight = classify(
            """
            Total      ${'$'}42.90
            Tip suggestion ${'$'}8.00
            Thank you!
            """
        )
        assertEquals("${'$'}42.90", insight.entities["total"])
    }

    @Test
    fun aLinkIsPickedOutWhereverItAppears() {
        val insight = classify(
            """
            Check this out
            https://example.com/some/article?ref=share
            """
        )
        assertEquals("https://example.com/some/article?ref=share", insight.entities["url"])
    }

    @Test
    fun bookingCodesAreOnlyLookedForOnTickets() {
        val code = classify(
            """
            Boarding Pass
            Gate B12 Seat 24A
            Confirmation ABC123
            Flight departure 10:45
            """
        )
        assertEquals("ABC123", code.entities["code"])

        // The same six-character token in a chat is not a booking reference.
        val chat = classify(
            """
            Maya
            online
            did you see ABC123 in the doc? 18:20
            yeah just now 18:21
            cool see you there 18:22
            delivered
            """
        )
        assertEquals(ScreenshotKind.CHAT, chat.kind)
        assertNull(chat.entities["code"])
    }

    @Test
    fun onlyTheFirstFewHundredCharactersAreKeptAsASample() {
        val long = (1..400).joinToString(" ") { "word$it" }
        val insight = classify(long)

        assertEquals(400, insight.textSample.length)
        assertTrue(long.startsWith(insight.textSample))
    }

    // ------------------------------------------------------- the confidence

    @Test
    fun confidenceIsBoundedAndRounded() {
        val samples = listOf(
            "Total ${'$'}42.90 subtotal ${'$'}38.40 tax ${'$'}4.50 receipt paid",
            "Add to cart Buy now In stock Free shipping reviews ${'$'}20.00",
            "hello there",
            "12 min 4.5 km route directions eta",
        )
        for (text in samples) {
            val insight = classify(text, blocks = 4)
            assertTrue("${insight.kind} scored ${insight.confidence}", insight.confidence in 0.0..0.95)
            assertEquals(insight.confidence, (insight.confidence * 100).toInt() / 100.0, 1e-9)
        }
    }

    @Test
    fun anObviousReceiptBeatsAnAmbiguousOne() {
        val obvious = classify(
            """
            Total      ${'$'}42.90
            Subtotal   ${'$'}38.40
            Tax         ${'$'}4.50
            Receipt #4821 paid with VISA
            """
        )
        val ambiguous = classify("Coffee ${'$'}4.50 total")

        assertEquals(ScreenshotKind.RECEIPT, obvious.kind)
        assertTrue(
            "${obvious.confidence} should beat ${ambiguous.confidence}",
            obvious.confidence > ambiguous.confidence,
        )
    }

    @Test
    fun textIsAnalysedButNeverInFull() {
        // Only the first 4 000 characters are considered, so a receipt hidden
        // at the end of a huge OCR dump does not swing the verdict.
        val filler = (1..2_000).joinToString(" ") { "lorem ipsum dolor" }
        val insight = classify("$filler Total ${'$'}42.90 receipt paid tax subtotal")

        assertEquals(ScreenshotKind.DOCUMENT, insight.kind)
    }

    // ------------------------------------------------------ stats / history

    @Test
    fun deliveredInsightsAreCounted() {
        ScreenshotIQ.deliver(classify("Total ${'$'}42.90 receipt paid tax subtotal"))
        ScreenshotIQ.deliver(classify("Total ${'$'}9.90 receipt paid tax subtotal"))
        ScreenshotIQ.deliver(classify("hello there"))

        assertEquals(2, ScreenshotIQ.stats()[ScreenshotKind.RECEIPT])
        assertEquals(1, ScreenshotIQ.stats()[ScreenshotKind.OTHER])
    }

    @Test
    fun historyComesBackNewestFirstAndCanBeLimited() {
        val receipt = classify("Total ${'$'}42.90 receipt paid tax subtotal")
        val other = classify("hello there")
        ScreenshotIQ.deliver(receipt)
        ScreenshotIQ.deliver(other)

        val recent = ScreenshotIQ.recent()
        assertEquals(listOf(ScreenshotKind.OTHER, ScreenshotKind.RECEIPT), recent.map { it.second })
        assertEquals(other.confidence, recent.first().third, 1e-9)
        assertEquals(1, ScreenshotIQ.recent(limit = 1).size)
    }

    @Test
    fun onlyTheVerdictIsKeptNeverTheText() {
        val insight = classify(
            """
            Total      ${'$'}42.90
            Card ending 4242
            Receipt #4821 paid tax subtotal
            """
        )
        ScreenshotIQ.deliver(insight)

        val json = ScreenshotIQ.exportJson()
        assertFalse("the export must not carry recognised text", json.contains("4242"))
        assertFalse(json.contains("42.90"))

        val root = JSONObject(json)
        assertEquals(1, root.getJSONObject("totals").getInt("RECEIPT"))
        val entry = root.getJSONArray("history").getJSONObject(0)
        assertEquals("RECEIPT", entry.getString("kind"))
        assertEquals(insight.at, entry.getLong("at"))
        assertEquals(insight.confidence, entry.getDouble("confidence"), 1e-9)
    }

    @Test
    fun resetForgetsTheHistory() {
        ScreenshotIQ.deliver(classify("Total ${'$'}42.90 receipt paid tax subtotal"))
        ScreenshotIQ.reset()

        assertTrue(ScreenshotIQ.stats().isEmpty())
        assertTrue(ScreenshotIQ.recent().isEmpty())
    }

    @Test
    fun everyKindHasALabelAndAnAction() {
        for (kind in ScreenshotKind.entries) {
            assertTrue(kind.name, kind.label.isNotBlank())
        }
        val insight = classify("hello there")
        assertTrue(insight.suggestions.all { it.id.isNotBlank() && it.label.isNotBlank() })
    }
}
