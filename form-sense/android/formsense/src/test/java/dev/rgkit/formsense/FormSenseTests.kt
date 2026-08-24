package dev.rgkit.formsense

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Replays whole form attempts through the public tracker API with
 * [FormSense.clock] hand-cranked, then reads the aggregates back out of
 * [FormSense.report]. No Context, so persistence no-ops.
 */
class FormSenseTests {

    private var now = 1_700_000_000_000L

    @Before
    fun setUp() {
        FormSense.clock = { now }
        FormSense.reset()
    }

    @After
    fun tearDown() {
        FormSense.reset()
        FormSense.clock = { System.currentTimeMillis() }
    }

    private fun advance(ms: Long) { now += ms }

    private fun report(formId: String = "signup"): FormReport =
        requireNotNull(FormSense.report(formId)) { "no report for $formId" }

    private fun field(formId: String = "signup", fieldId: String) =
        FormSense.form(formId).field(fieldId)

    /** One clean pass over a field: focus, type [chars], leave after [dwellMs]. */
    private fun fillField(fieldId: String, chars: Int, dwellMs: Long, formId: String = "signup") {
        val f = field(formId, fieldId)
        f.focused()
        advance(dwellMs)
        f.textChanged(chars)
        f.blurred()
    }

    // ------------------------------------------------------- attempt counting

    @Test
    fun anAttemptStartsAtTheFirstFocusAndConvertsOnSubmit() {
        fillField("email", chars = 12, dwellMs = 4_000)
        FormSense.form("signup").submitted()

        val r = report()
        assertEquals("signup", r.formId)
        assertEquals(1, r.starts)
        assertEquals(1, r.submits)
        assertEquals(0, r.abandons)
        assertEquals(100, r.conversionPercent)
    }

    @Test
    fun typingAloneAlsoStartsTheAttempt() {
        // Some fields are filled by autofill/paste without a focus event.
        field(fieldId = "email").textChanged(9)
        FormSense.form("signup").submitted()

        assertEquals(1, report().starts)
    }

    @Test
    fun conversionIsSubmitsOverStarts() {
        repeat(2) {
            fillField("email", 10, 2_000)
            FormSense.form("signup").abandoned()
        }
        fillField("email", 10, 2_000)
        FormSense.form("signup").submitted()

        val r = report()
        assertEquals(3, r.starts)
        assertEquals(1, r.submits)
        assertEquals(2, r.abandons)
        assertEquals(33, r.conversionPercent)
    }

    @Test
    fun anAttemptEndsOnlyOnce() {
        fillField("email", 10, 1_000)
        val form = FormSense.form("signup")
        form.submitted()
        form.submitted()
        form.abandoned()

        assertEquals(1, report().submits)
        assertEquals(0, report().abandons)
    }

    @Test
    fun aDiscardedAttemptIsNeitherConvertedNorAbandoned() {
        fillField("email", 10, 1_000)
        FormSense.form("signup").discard()

        val r = report()
        assertEquals(1, r.starts) // the start already happened
        assertEquals(0, r.submits)
        assertEquals(0, r.abandons)
    }

    @Test
    fun anAttemptThatNeverStartedIsNotAnAbandon() {
        FormSense.form("signup").abandoned()
        assertNull(FormSense.report("signup"))
    }

    @Test
    fun formsAreTrackedSeparately() {
        fillField("email", 10, 1_000, formId = "signup")
        FormSense.form("signup").submitted()
        fillField("card", 16, 9_000, formId = "checkout")
        FormSense.form("checkout").abandoned()

        assertEquals(100, report("signup").conversionPercent)
        assertEquals(0, report("checkout").conversionPercent)
        assertNull(FormSense.report("never_seen"))
    }

    // ------------------------------------------------------------- completion

    @Test
    fun completionTimeIsTheMedianOfSubmittedAttempts() {
        for (ms in listOf(10_000L, 4_000L, 6_000L)) {
            fillField("email", 10, ms)
            FormSense.form("signup").submitted()
        }
        assertEquals(6_000L, report().medianCompletionMs)
    }

    @Test
    fun abandonedAttemptsDoNotSkewCompletionTime() {
        fillField("email", 10, 60_000)
        FormSense.form("signup").abandoned()
        fillField("email", 10, 5_000)
        FormSense.form("signup").submitted()

        assertEquals(5_000L, report().medianCompletionMs)
    }

    @Test
    fun aFormWithNoSubmitHasNoCompletionTime() {
        fillField("email", 10, 3_000)
        FormSense.form("signup").abandoned()
        assertEquals(0L, report().medianCompletionMs)
    }

    // ------------------------------------------------------------ field time

    @Test
    fun timeIsCreditedToTheFocusedField() {
        val email = field(fieldId = "email")
        val phone = field(fieldId = "phone")
        email.focused()
        advance(6_000)
        phone.focused()   // moving on flushes email's time even without a blur
        advance(2_000)
        phone.blurred()
        FormSense.form("signup").submitted()

        val fields = report().fields.associateBy { it.fieldId }
        assertEquals(6_000L, fields.getValue("email").avgActiveMs)
        assertEquals(2_000L, fields.getValue("phone").avgActiveMs)
    }

    @Test
    fun timeIsAveragedAcrossVisitsToTheSameField() {
        val email = field(fieldId = "email")
        email.focused(); advance(4_000); email.blurred()
        email.focused(); advance(2_000); email.blurred()
        FormSense.form("signup").submitted()

        val f = report().fields.single()
        assertEquals(2, f.visits)
        assertEquals(3_000L, f.avgActiveMs)
    }

    @Test
    fun aFieldLeftFocusedOverAWeekendIsNotCountedAsTime() {
        // The 10-minute ceiling keeps a backgrounded form from poisoning the average.
        val email = field(fieldId = "email")
        email.focused()
        advance(3 * 86_400_000L)
        email.blurred()
        FormSense.form("signup").submitted()

        assertEquals(0L, report().fields.single().avgActiveMs)
    }

    // ------------------------------------------------------------ corrections

    @Test
    fun deletingWhatYouTypedShowsUpAsACorrectionRatio() {
        val email = field(fieldId = "email")
        email.focused()
        email.textChanged(10)
        email.textChanged(4)  // deleted 6 of 10
        email.textChanged(9)  // typed 5 more
        email.blurred()
        FormSense.form("signup").submitted()

        // 15 typed, 6 deleted.
        assertEquals(0.4, report().fields.single().correctionRatio, 1e-9)
    }

    @Test
    fun aFieldThatWasNeverTypedInHasNoCorrections() {
        fillField("email", chars = 0, dwellMs = 1_000)
        FormSense.form("signup").submitted()
        assertEquals(0.0, report().fields.single().correctionRatio, 1e-9)
    }

    // ---------------------------------------------------------- friction math

    @Test
    fun aQuickCleanFieldHasNoFriction() {
        fillField("email", chars = 12, dwellMs = 3_000)
        FormSense.form("signup").submitted()

        val f = report().fields.single()
        assertEquals(0, f.frictionScore)
        assertEquals("looks healthy", f.suggestion)
    }

    @Test
    fun aSlowFieldScoresForEverySecondOverEight() {
        // 16 s active: (16000 - 8000) / 800 = 10 points, nothing else firing.
        fillField("email", chars = 12, dwellMs = 16_000)
        FormSense.form("signup").submitted()

        val f = report().fields.single()
        assertEquals(10, f.frictionScore)
        assertTrue(f.suggestion, f.suggestion.contains("takes very long"))
    }

    @Test
    fun heavyRetypingScoresOnItsOwn() {
        // No dwell at all, so only the correction term is in play:
        // 8 deleted of 20 typed = 0.4 -> (0.40 - 0.15) * 80 = 20 points.
        val email = field(fieldId = "email")
        email.focused()
        email.textChanged(20)
        email.textChanged(12)
        email.blurred()
        FormSense.form("signup").submitted()

        val f = report().fields.single()
        assertEquals(20, f.frictionScore)
        assertTrue(f.suggestion, f.suggestion.contains("heavy retyping"))
    }

    @Test
    fun validationErrorsAreCappedAtFifteen() {
        val email = field(fieldId = "email")
        email.focused()
        repeat(3) { email.errorShown() }
        email.blurred()
        FormSense.form("signup").submitted()

        val f = report().fields.single()
        assertEquals(3, f.errorsShown)
        assertEquals(15, f.frictionScore)
        assertTrue(f.suggestion, f.suggestion.contains("validate inline"))
    }

    @Test
    fun theFieldPeopleGiveUpOnCarriesTheAbandon() {
        val email = field(fieldId = "email")
        email.focused(); email.textChanged(8); email.blurred()
        val card = field(fieldId = "card_number")
        card.focused()
        FormSense.form("signup").abandoned()

        val fields = report().fields.associateBy { it.fieldId }
        assertEquals(1.0, fields.getValue("card_number").abandonShare, 1e-9)
        assertEquals(0.0, fields.getValue("email").abandonShare, 1e-9)
        assertEquals(35, fields.getValue("card_number").frictionScore)
        assertTrue(
            fields.getValue("card_number").suggestion,
            fields.getValue("card_number").suggestion.contains("give up here"),
        )
    }

    @Test
    fun comingBackToAFieldOverAndOverIsFriction() {
        // Four visits across two attempts = refocus 2.0 -> (2.0 - 1.3) * 12 = 8.4.
        repeat(2) {
            val email = field(fieldId = "email")
            email.focused(); email.blurred()
            email.focused(); email.blurred()
            FormSense.form("signup").submitted()
        }

        val f = report().fields.single()
        assertEquals(2.0, f.refocusAvg, 1e-9)
        assertEquals(8, f.frictionScore)
        assertTrue(f.suggestion, f.suggestion.contains("keep returning"))
    }

    @Test
    fun frictionNeverExceedsAHundred() {
        val card = field(fieldId = "card_number")
        card.focused()
        advance(120_000)
        card.textChanged(30)
        card.textChanged(2)
        repeat(10) { card.errorShown() }
        FormSense.form("signup").abandoned()

        val f = report().fields.single()
        assertTrue("score ${f.frictionScore}", f.frictionScore in 0..100)
        assertEquals(100, f.frictionScore)
    }

    @Test
    fun theWorstFieldIsReportedFirst() {
        val email = field(fieldId = "email")
        email.focused(); advance(1_000); email.textChanged(10); email.blurred()
        val card = field(fieldId = "card_number")
        card.focused(); advance(40_000); card.textChanged(16); card.textChanged(4)
        FormSense.form("signup").abandoned()

        val scores = report().fields
        assertEquals("card_number", scores.first().fieldId)
        assertTrue(scores.first().frictionScore > scores.last().frictionScore)
    }

    // ---------------------------------------------------------------- reports

    @Test
    fun reportsComeBackWorstConvertingFirst() {
        fillField("email", 10, 1_000, formId = "good")
        FormSense.form("good").submitted()
        fillField("card", 10, 1_000, formId = "bad")
        FormSense.form("bad").abandoned()

        assertEquals(listOf("bad", "good"), FormSense.reports().map { it.formId })
    }

    @Test
    fun exportCarriesTheRawAggregates() {
        val email = field(fieldId = "email")
        email.focused(); advance(3_000); email.textChanged(10); email.textChanged(7); email.errorShown()
        FormSense.form("signup").submitted()

        val forms = JSONObject(FormSense.exportJson()).getJSONObject("forms")
        val signup = forms.getJSONObject("signup")
        assertEquals(1, signup.getInt("starts"))
        assertEquals(1, signup.getInt("submits"))
        assertEquals(1, signup.getJSONArray("completionSamples").length())
        val emailAgg = signup.getJSONObject("fields").getJSONObject("email")
        assertEquals(1, emailAgg.getInt("visits"))
        assertEquals(10, emailAgg.getInt("charsTyped"))
        assertEquals(3, emailAgg.getInt("charsDeleted"))
        assertEquals(1, emailAgg.getInt("errors"))
    }

    @Test
    fun resetForgetsEveryFormAndLiveAttempt() {
        fillField("email", 10, 5_000)
        FormSense.reset()

        assertNull(FormSense.report("signup"))
        assertTrue(FormSense.reports().isEmpty())

        // The live tracker is gone too, so the next focus starts a fresh attempt.
        fillField("email", 10, 1_000)
        FormSense.form("signup").submitted()
        assertEquals(1, report().starts)
    }
}
