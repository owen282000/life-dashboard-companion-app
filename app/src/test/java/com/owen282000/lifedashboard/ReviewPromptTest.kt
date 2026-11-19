package com.owen282000.lifedashboard

import com.owen282000.lifedashboard.ReviewPrompt.History
import com.owen282000.lifedashboard.ReviewPrompt.Moment
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the Google Play build asks for a rating (P1-28, P1-43): the same rule as the iPhone app's
 * ReviewPrompt. Only after a clean delivery or a finished backfill, not in the visit that fixed
 * a failure, with a long pause after a dialog that was seen and a short one after a request
 * that Play kept to itself.
 */
class ReviewPromptTest {

    private val hour = 60L * 60 * 1000
    private val day = 24 * hour
    private val now = 1_792_000_000_000L

    private fun good() = ReviewPrompt.State(
        firstUseMillis = now - 30 * day,
        lastSyncMillis = now - hour,
        lastSyncSucceeded = true,
        failureStreak = 0,
        partialStreak = 0,
        pending = 0,
        lastFailureMillis = null
    )

    private val everyMoment = Moment.entries

    @Test
    fun aRecentSyncDeliveredToEveryAddressIsAGoodMoment() {
        everyMoment.forEach { assertTrue(it.name, ReviewPrompt.isGoodMoment(it, good(), now)) }
    }

    @Test
    fun aFailedLastSyncIsNot() {
        everyMoment.forEach { assertFalse(it.name, ReviewPrompt.isGoodMoment(it, good().copy(lastSyncSucceeded = false), now)) }
    }

    @Test
    fun failingSyncsAreNot() {
        everyMoment.forEach { assertFalse(it.name, ReviewPrompt.isGoodMoment(it, good().copy(failureStreak = 1), now)) }
    }

    @Test
    fun anAddressThatMissesPayloadsIsNot() {
        everyMoment.forEach { assertFalse(it.name, ReviewPrompt.isGoodMoment(it, good().copy(partialStreak = 2), now)) }
    }

    @Test
    fun payloadsWaitingInTheOutboxAreNot() {
        everyMoment.forEach { assertFalse(it.name, ReviewPrompt.isGoodMoment(it, good().copy(pending = 3), now)) }
    }

    @Test
    fun nothingDeliveredWithinADayIsNot() {
        assertTrue(ReviewPrompt.isGoodMoment(Moment.OPENED, good().copy(lastSyncMillis = now - day), now))
        assertFalse(ReviewPrompt.isGoodMoment(Moment.OPENED, good().copy(lastSyncMillis = now - 2 * day), now))
        assertFalse(ReviewPrompt.isGoodMoment(Moment.SYNC_DELIVERED, good().copy(lastSyncMillis = now - 2 * day), now))
        assertFalse(ReviewPrompt.isGoodMoment(Moment.OPENED, good().copy(lastSyncMillis = null), now))
    }

    @Test
    fun theFirstDayDoesNotAskOnOpeningOrSyncing() {
        for (moment in listOf(Moment.OPENED, Moment.SYNC_DELIVERED)) {
            assertTrue(ReviewPrompt.isGoodMoment(moment, good().copy(firstUseMillis = now - day), now))
            assertFalse(ReviewPrompt.isGoodMoment(moment, good().copy(firstUseMillis = now - day + 1), now))
            assertFalse(ReviewPrompt.isGoodMoment(moment, good().copy(firstUseMillis = null), now))
        }
    }

    @Test
    fun aFinishedBackfillCountsFromTheFirstDay() {
        val setupDay = good().copy(firstUseMillis = now - hour, lastFailureMillis = now - 2 * hour)
        assertTrue(ReviewPrompt.isGoodMoment(Moment.BACKFILL_DONE, setupDay, now))
        assertTrue(ReviewPrompt.isGoodMoment(Moment.BACKFILL_DONE, setupDay.copy(firstUseMillis = null), now))
        assertFalse(ReviewPrompt.isGoodMoment(Moment.BACKFILL_DONE, setupDay.copy(pending = 1), now))
    }

    @Test
    fun theVisitThatFixedAFailureDoesNotAsk() {
        val justFixed = good().copy(lastFailureMillis = now - 2 * hour)
        assertFalse(ReviewPrompt.isGoodMoment(Moment.OPENED, justFixed, now))
        assertFalse(ReviewPrompt.isGoodMoment(Moment.SYNC_DELIVERED, justFixed, now))
        assertFalse(ReviewPrompt.isGoodMoment(Moment.OPENED, good().copy(lastFailureMillis = now - day + 1), now))
        assertTrue(ReviewPrompt.isGoodMoment(Moment.OPENED, good().copy(lastFailureMillis = now - day), now))
    }

    @Test
    fun theFirstRequestNeedsNoWait() {
        assertTrue(ReviewPrompt.mayRequest(History(), now))
    }

    @Test
    fun aDialogThatWasSeenRestsFourMonths() {
        fun seen(daysAgo: Long) = History(lastRequestMillis = now - daysAgo * day, lastRequestShown = true, shownCount = 1)
        assertFalse(ReviewPrompt.mayRequest(seen(14), now))
        assertFalse(ReviewPrompt.mayRequest(seen(119), now))
        assertTrue(ReviewPrompt.mayRequest(seen(120), now))
    }

    @Test
    fun aRequestThatPlayKeptToItselfIsTriedAgainAfterTwoWeeks() {
        fun unseen(daysAgo: Long) = History(lastRequestMillis = now - daysAgo * day, lastRequestShown = false)
        assertFalse(ReviewPrompt.mayRequest(unseen(0), now))
        assertFalse(ReviewPrompt.mayRequest(unseen(13), now))
        assertTrue(ReviewPrompt.mayRequest(unseen(14), now))
    }

    @Test
    fun threeDialogsSeenIsTheEnd() {
        val longAgo = now - 400 * day
        assertTrue(ReviewPrompt.mayRequest(History(longAgo, lastRequestShown = true, shownCount = 2), now))
        assertFalse(ReviewPrompt.mayRequest(History(longAgo, lastRequestShown = true, shownCount = 3), now))
    }

    @Test
    fun askingNeedsBothAGoodMomentAndARestedDialog() {
        assertTrue(ReviewPrompt.shouldAsk(Moment.OPENED, good(), History(), now))
        assertFalse(ReviewPrompt.shouldAsk(Moment.OPENED, good().copy(failureStreak = 1), History(), now))
        assertFalse(ReviewPrompt.shouldAsk(Moment.OPENED, good(), History(now - day, lastRequestShown = true, shownCount = 1), now))
    }

    @Test
    fun aFlowThatReturnsAtOnceShowedNoDialog() {
        assertFalse(ReviewPrompt.wasShown(0))
        assertFalse(ReviewPrompt.wasShown(1_499))
        assertTrue(ReviewPrompt.wasShown(1_500))
        assertTrue(ReviewPrompt.wasShown(20_000))
    }

    @Test
    fun theCardComesAfterTwoWeeksAndAWeekAfterTheDialog() {
        val asked = History(lastRequestMillis = now - 7 * day, lastRequestShown = true, shownCount = 1)
        assertTrue(ReviewPrompt.showsCard(good(), asked, cardClosed = false, now = now))
        assertFalse("closed or used", ReviewPrompt.showsCard(good(), asked, cardClosed = true, now = now))
        assertFalse("the dialog comes first", ReviewPrompt.showsCard(good(), History(), cardClosed = false, now = now))
        assertFalse("too soon after the dialog", ReviewPrompt.showsCard(good(), asked.copy(lastRequestMillis = now - 6 * day), cardClosed = false, now = now))
        assertFalse("too new", ReviewPrompt.showsCard(good().copy(firstUseMillis = now - 13 * day), asked, cardClosed = false, now = now))
        assertTrue(ReviewPrompt.showsCard(good().copy(firstUseMillis = now - 14 * day), asked, cardClosed = false, now = now))
    }

    @Test
    fun theCardNeverStandsNextToAProblem() {
        val asked = History(lastRequestMillis = now - 30 * day)
        assertFalse(ReviewPrompt.showsCard(good().copy(failureStreak = 1), asked, cardClosed = false, now = now))
        assertFalse(ReviewPrompt.showsCard(good().copy(pending = 1), asked, cardClosed = false, now = now))
        assertFalse(ReviewPrompt.showsCard(good().copy(lastFailureMillis = now - hour), asked, cardClosed = false, now = now))
        assertFalse(ReviewPrompt.showsCard(good().copy(lastSyncMillis = now - 3 * day), asked, cardClosed = false, now = now))
    }

    @Test
    fun theStarCardComesAtTheFirstGoodMomentInFront() {
        assertTrue(ReviewPrompt.showsStarCard(good(), cardClosed = false, now = now))
        assertTrue(ReviewPrompt.showsStarCard(good().copy(firstUseMillis = now - day), cardClosed = false, now = now))
        assertFalse("the first day", ReviewPrompt.showsStarCard(good().copy(firstUseMillis = now - hour), cardClosed = false, now = now))
        assertFalse("before the tabs ever showed", ReviewPrompt.showsStarCard(good().copy(firstUseMillis = null), cardClosed = false, now = now))
    }

    @Test
    fun theStarCardIsAskedOnceAndNeverNextToAProblem() {
        assertFalse("closed or used", ReviewPrompt.showsStarCard(good(), cardClosed = true, now = now))
        assertFalse(ReviewPrompt.showsStarCard(good().copy(failureStreak = 1), cardClosed = false, now = now))
        assertFalse(ReviewPrompt.showsStarCard(good().copy(partialStreak = 1), cardClosed = false, now = now))
        assertFalse(ReviewPrompt.showsStarCard(good().copy(pending = 2), cardClosed = false, now = now))
        assertFalse(ReviewPrompt.showsStarCard(good().copy(lastSyncSucceeded = false), cardClosed = false, now = now))
        assertFalse(ReviewPrompt.showsStarCard(good().copy(lastFailureMillis = now - hour), cardClosed = false, now = now))
        assertFalse(ReviewPrompt.showsStarCard(good().copy(lastSyncMillis = now - 3 * day), cardClosed = false, now = now))
    }
}
