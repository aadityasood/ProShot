package com.proshot.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class CaptureTimestampCorrelatorTest {

    private class TestCandidate(
        val id: String,
        val ts: Long,
        val throwOnExtract: Boolean = false
    ) {
        private val releaseCountAtomic = AtomicInteger(0)
        val releaseCount: Int get() = releaseCountAtomic.get()

        fun release() {
            releaseCountAtomic.incrementAndGet()
        }
    }

    private fun createCorrelator(
        tag: Any = Any(),
        onOutcome: (CorrelationOutcome<TestCandidate>) -> Unit
    ): CaptureTimestampCorrelator<TestCandidate> {
        return CaptureTimestampCorrelator(
            requestTag = tag,
            timestampExtractor = {
                if (it.throwOnExtract) throw IllegalStateException("Extractor error on ${it.id}")
                it.ts
            },
            releaser = { it.release() },
            onOutcome = onOutcome
        )
    }

    @Test
    fun exactResultFirstSuccess() {
        val tagObj = Any()
        var outcome: CorrelationOutcome<TestCandidate>? = null
        val correlator = createCorrelator(tagObj) { outcome = it }
        correlator.registerSequenceId(1)

        correlator.onCaptureCompleted(sequenceId = 1, sensorTimestamp = 1000L, tag = tagObj)
        val c1 = TestCandidate("c1", 1000L)
        correlator.onCandidateAvailable(c1)

        assertTrue(outcome is CorrelationOutcome.Success)
        val success = outcome as CorrelationOutcome.Success
        assertEquals(c1, success.candidate)
        assertEquals(1000L, success.timestamp)
        assertEquals(0, c1.releaseCount)
    }

    @Test
    fun exactImageFirstSuccess() {
        val tagObj = Any()
        var outcome: CorrelationOutcome<TestCandidate>? = null
        val correlator = createCorrelator(tagObj) { outcome = it }
        correlator.registerSequenceId(1)

        val c1 = TestCandidate("c1", 1000L)
        correlator.onCandidateAvailable(c1)
        correlator.onCaptureCompleted(sequenceId = 1, sensorTimestamp = 1000L, tag = tagObj)

        assertTrue(outcome is CorrelationOutcome.Success)
        val success = outcome as CorrelationOutcome.Success
        assertEquals(c1, success.candidate)
        assertEquals(1000L, success.timestamp)
        assertEquals(0, c1.releaseCount)
    }

    @Test
    fun twoExactCandidatesBeforeSequenceRegistrationRetainsFirstAndReleasesDuplicate() {
        val tagObj = Any()
        var outcome: CorrelationOutcome<TestCandidate>? = null
        val correlator = createCorrelator(tagObj) { outcome = it }

        val c1 = TestCandidate("c1", 1000L)
        val c2 = TestCandidate("c2", 1000L)

        correlator.onCaptureCompleted(sequenceId = 42, sensorTimestamp = 1000L, tag = tagObj)

        correlator.onCandidateAvailable(c1)
        assertNull(outcome)
        assertEquals(0, c1.releaseCount)

        correlator.onCandidateAvailable(c2)
        assertNull(outcome)
        assertEquals(1, c2.releaseCount)

        correlator.registerSequenceId(42)

        assertTrue(outcome is CorrelationOutcome.Success)
        val success = outcome as CorrelationOutcome.Success
        assertEquals(c1, success.candidate)
        assertEquals(0, c1.releaseCount)
        assertEquals(1, c2.releaseCount)
    }

    @Test
    fun exactPairBeforeSequenceRegistrationFollowedByMismatchingRegistrationReleasesCandidateAndFails() {
        val tagObj = Any()
        var outcome: CorrelationOutcome<TestCandidate>? = null
        val correlator = createCorrelator(tagObj) { outcome = it }

        val c1 = TestCandidate("c1", 1000L)
        correlator.onCandidateAvailable(c1)
        correlator.onCaptureCompleted(sequenceId = 42, sensorTimestamp = 1000L, tag = tagObj)

        assertNull(outcome)
        assertEquals(0, c1.releaseCount)

        correlator.registerSequenceId(99)

        assertTrue(outcome is CorrelationOutcome.Failure)
        assertEquals(1, c1.releaseCount)
    }

    @Test
    fun multiplePreResultCandidatesSelectsExactAndReleasesEveryStaleCandidate() {
        val tagObj = Any()
        var outcome: CorrelationOutcome<TestCandidate>? = null
        val correlator = createCorrelator(tagObj) { outcome = it }
        correlator.registerSequenceId(1)

        val c1 = TestCandidate("c1", 100L)
        val c2 = TestCandidate("c2", 200L)
        val c3 = TestCandidate("c3", 300L)

        correlator.onCandidateAvailable(c1)
        correlator.onCandidateAvailable(c2)
        correlator.onCandidateAvailable(c3)

        correlator.onCaptureCompleted(sequenceId = 1, sensorTimestamp = 200L, tag = tagObj)

        assertTrue(outcome is CorrelationOutcome.Success)
        val success = outcome as CorrelationOutcome.Success
        assertEquals(c2, success.candidate)
        assertEquals(1, c1.releaseCount)
        assertEquals(0, c2.releaseCount)
        assertEquals(1, c3.releaseCount)
    }

    @Test
    fun resultKnownStaleCandidatesReleasedImmediatelyAndLaterExactCandidateWins() {
        val tagObj = Any()
        var outcome: CorrelationOutcome<TestCandidate>? = null
        val correlator = createCorrelator(tagObj) { outcome = it }
        correlator.registerSequenceId(1)

        correlator.onCaptureCompleted(sequenceId = 1, sensorTimestamp = 200L, tag = tagObj)

        val c1 = TestCandidate("c1", 100L)
        correlator.onCandidateAvailable(c1)
        assertEquals(1, c1.releaseCount)
        assertNull(outcome)

        val c3 = TestCandidate("c3", 300L)
        correlator.onCandidateAvailable(c3)
        assertEquals(1, c3.releaseCount)
        assertNull(outcome)

        val c2 = TestCandidate("c2", 200L)
        correlator.onCandidateAvailable(c2)

        assertTrue(outcome is CorrelationOutcome.Success)
        val success = outcome as CorrelationOutcome.Success
        assertEquals(c2, success.candidate)
        assertEquals(0, c2.releaseCount)
    }

    @Test
    fun fourthRetainedPreResultCandidateFailsAtThreeBound() {
        val tagObj = Any()
        var outcome: CorrelationOutcome<TestCandidate>? = null
        val correlator = createCorrelator(tagObj) { outcome = it }

        val c1 = TestCandidate("c1", 100L)
        val c2 = TestCandidate("c2", 200L)
        val c3 = TestCandidate("c3", 300L)
        val c4 = TestCandidate("c4", 400L)

        correlator.onCandidateAvailable(c1)
        correlator.onCandidateAvailable(c2)
        correlator.onCandidateAvailable(c3)
        assertEquals(0, c1.releaseCount)
        assertEquals(0, c2.releaseCount)
        assertEquals(0, c3.releaseCount)

        correlator.onCandidateAvailable(c4)

        assertTrue(outcome is CorrelationOutcome.Failure)
        assertEquals(1, c1.releaseCount)
        assertEquals(1, c2.releaseCount)
        assertEquals(1, c3.releaseCount)
        assertEquals(1, c4.releaseCount)
    }

    @Test
    fun captureFailureBeforeSequenceRegistrationReleasesPendingCandidateOnceAndRemainsTerminal() {
        val tagObj = Any()
        val outcomes = mutableListOf<CorrelationOutcome<TestCandidate>>()
        val correlator = createCorrelator(tagObj) { outcomes.add(it) }
        val pending = TestCandidate("pending", 100L)
        correlator.onCandidateAvailable(pending)

        correlator.onCaptureFailed(sequenceId = 1, tag = tagObj)

        assertEquals(1, outcomes.size)
        assertTrue(outcomes.single() is CorrelationOutcome.Failure)
        assertEquals(1, pending.releaseCount)

        val late = TestCandidate("late", 101L)
        correlator.onCandidateAvailable(late)
        assertEquals(1, late.releaseCount)
        assertEquals(1, outcomes.size)
    }

    @Test
    fun captureFailureAfterMatchingSequenceRegistrationReleasesPendingCandidateOnceAndRemainsTerminal() {
        val tagObj = Any()
        val outcomes = mutableListOf<CorrelationOutcome<TestCandidate>>()
        val correlator = createCorrelator(tagObj) { outcomes.add(it) }
        correlator.registerSequenceId(2)
        val pending = TestCandidate("pending", 200L)
        correlator.onCandidateAvailable(pending)

        correlator.onCaptureFailed(sequenceId = 2, tag = tagObj)

        assertEquals(1, outcomes.size)
        assertTrue(outcomes.single() is CorrelationOutcome.Failure)
        assertEquals(1, pending.releaseCount)

        val late = TestCandidate("late", 201L)
        correlator.onCandidateAvailable(late)
        assertEquals(1, late.releaseCount)
        assertEquals(1, outcomes.size)
    }

    @Test
    fun bufferLossBeforeSequenceRegistrationReleasesPendingCandidateOnceAndRemainsTerminal() {
        val tagObj = Any()
        val outcomes = mutableListOf<CorrelationOutcome<TestCandidate>>()
        val correlator = createCorrelator(tagObj) { outcomes.add(it) }
        val pending = TestCandidate("pending", 300L)
        correlator.onCandidateAvailable(pending)

        correlator.onCaptureBufferLost(tag = tagObj, frameNumber = 30L)

        assertEquals(1, outcomes.size)
        assertTrue(outcomes.single() is CorrelationOutcome.Failure)
        assertEquals(1, pending.releaseCount)

        val late = TestCandidate("late", 301L)
        correlator.onCandidateAvailable(late)
        assertEquals(1, late.releaseCount)
        assertEquals(1, outcomes.size)
    }

    @Test
    fun bufferLossAfterSequenceRegistrationReleasesPendingCandidateOnceAndRemainsTerminal() {
        val tagObj = Any()
        val outcomes = mutableListOf<CorrelationOutcome<TestCandidate>>()
        val correlator = createCorrelator(tagObj) { outcomes.add(it) }
        correlator.registerSequenceId(4)
        val pending = TestCandidate("pending", 400L)
        correlator.onCandidateAvailable(pending)

        correlator.onCaptureBufferLost(tag = tagObj, frameNumber = 40L)

        assertEquals(1, outcomes.size)
        assertTrue(outcomes.single() is CorrelationOutcome.Failure)
        assertEquals(1, pending.releaseCount)

        val late = TestCandidate("late", 401L)
        correlator.onCandidateAvailable(late)
        assertEquals(1, late.releaseCount)
        assertEquals(1, outcomes.size)
    }

    @Test
    fun nullTimestampReleasesPendingCandidateOnceAndRemainsTerminal() {
        val tagObj = Any()
        val outcomes = mutableListOf<CorrelationOutcome<TestCandidate>>()
        val correlator = createCorrelator(tagObj) { outcomes.add(it) }
        val pending = TestCandidate("pending", 500L)
        correlator.onCandidateAvailable(pending)

        correlator.onCaptureCompleted(sequenceId = 5, sensorTimestamp = null, tag = tagObj)

        assertEquals(1, outcomes.size)
        assertTrue(outcomes.single() is CorrelationOutcome.Failure)
        assertEquals(1, pending.releaseCount)

        val late = TestCandidate("late", 501L)
        correlator.onCandidateAvailable(late)
        assertEquals(1, late.releaseCount)
        assertEquals(1, outcomes.size)
    }

    @Test
    fun wrongResultTagReleasesPendingCandidateOnceAndRemainsTerminal() {
        val tagObj = Any()
        val outcomes = mutableListOf<CorrelationOutcome<TestCandidate>>()
        val correlator = createCorrelator(tagObj) { outcomes.add(it) }
        val pending = TestCandidate("pending", 600L)
        correlator.onCandidateAvailable(pending)

        correlator.onCaptureCompleted(sequenceId = 6, sensorTimestamp = 600L, tag = Any())

        assertEquals(1, outcomes.size)
        assertTrue(outcomes.single() is CorrelationOutcome.Failure)
        assertEquals(1, pending.releaseCount)

        val late = TestCandidate("late", 601L)
        correlator.onCandidateAvailable(late)
        assertEquals(1, late.releaseCount)
        assertEquals(1, outcomes.size)
    }

    @Test
    fun duplicateSameTimestampResultReleasesExactPendingCandidateOnceAndRemainsTerminal() {
        val tagObj = Any()
        val outcomes = mutableListOf<CorrelationOutcome<TestCandidate>>()
        val correlator = createCorrelator(tagObj) { outcomes.add(it) }
        correlator.onCaptureCompleted(sequenceId = 7, sensorTimestamp = 700L, tag = tagObj)
        val pendingMatch = TestCandidate("pending-match", 700L)
        correlator.onCandidateAvailable(pendingMatch)
        assertEquals(0, pendingMatch.releaseCount)
        assertTrue(outcomes.isEmpty())

        correlator.onCaptureCompleted(sequenceId = 7, sensorTimestamp = 700L, tag = tagObj)

        assertEquals(1, outcomes.size)
        assertTrue(outcomes.single() is CorrelationOutcome.Failure)
        assertEquals(1, pendingMatch.releaseCount)

        val late = TestCandidate("late", 701L)
        correlator.onCandidateAvailable(late)
        assertEquals(1, late.releaseCount)
        assertEquals(1, outcomes.size)
    }

    @Test
    fun conflictingTimestampResultReleasesExactPendingCandidateOnceAndRemainsTerminal() {
        val tagObj = Any()
        val outcomes = mutableListOf<CorrelationOutcome<TestCandidate>>()
        val correlator = createCorrelator(tagObj) { outcomes.add(it) }
        correlator.onCaptureCompleted(sequenceId = 8, sensorTimestamp = 800L, tag = tagObj)
        val pendingMatch = TestCandidate("pending-match", 800L)
        correlator.onCandidateAvailable(pendingMatch)
        assertEquals(0, pendingMatch.releaseCount)
        assertTrue(outcomes.isEmpty())

        correlator.onCaptureCompleted(sequenceId = 8, sensorTimestamp = 801L, tag = tagObj)

        assertEquals(1, outcomes.size)
        assertTrue(outcomes.single() is CorrelationOutcome.Failure)
        assertEquals(1, pendingMatch.releaseCount)

        val late = TestCandidate("late", 802L)
        correlator.onCandidateAvailable(late)
        assertEquals(1, late.releaseCount)
        assertEquals(1, outcomes.size)
    }

    @Test
    fun sequenceCompletionBeforeRegistrationThenMismatchReleasesPendingCandidateOnceAndRemainsTerminal() {
        val tagObj = Any()
        val outcomes = mutableListOf<CorrelationOutcome<TestCandidate>>()
        val correlator = createCorrelator(tagObj) { outcomes.add(it) }
        val pending = TestCandidate("pending", 900L)
        correlator.onCandidateAvailable(pending)
        correlator.onCaptureSequenceCompleted(sequenceId = 9)
        assertTrue(outcomes.isEmpty())

        correlator.registerSequenceId(90)

        assertEquals(1, outcomes.size)
        assertTrue(outcomes.single() is CorrelationOutcome.Failure)
        assertEquals(1, pending.releaseCount)

        val late = TestCandidate("late", 901L)
        correlator.onCandidateAvailable(late)
        assertEquals(1, late.releaseCount)
        assertEquals(1, outcomes.size)
    }

    @Test
    fun sequenceCompletionMismatchAfterRegistrationReleasesPendingCandidateOnceAndRemainsTerminal() {
        val tagObj = Any()
        val outcomes = mutableListOf<CorrelationOutcome<TestCandidate>>()
        val correlator = createCorrelator(tagObj) { outcomes.add(it) }
        correlator.registerSequenceId(10)
        val pending = TestCandidate("pending", 1000L)
        correlator.onCandidateAvailable(pending)

        correlator.onCaptureSequenceCompleted(sequenceId = 100)

        assertEquals(1, outcomes.size)
        assertTrue(outcomes.single() is CorrelationOutcome.Failure)
        assertEquals(1, pending.releaseCount)

        val late = TestCandidate("late", 1001L)
        correlator.onCandidateAvailable(late)
        assertEquals(1, late.releaseCount)
        assertEquals(1, outcomes.size)
    }

    @Test
    fun sequenceAbortBeforeRegistrationReleasesPendingCandidateOnceAndRemainsTerminal() {
        val tagObj = Any()
        val outcomes = mutableListOf<CorrelationOutcome<TestCandidate>>()
        val correlator = createCorrelator(tagObj) { outcomes.add(it) }
        val pending = TestCandidate("pending", 1100L)
        correlator.onCandidateAvailable(pending)

        correlator.onCaptureSequenceAborted(sequenceId = 11)

        assertEquals(1, outcomes.size)
        assertTrue(outcomes.single() is CorrelationOutcome.Failure)
        assertEquals(1, pending.releaseCount)

        val late = TestCandidate("late", 1101L)
        correlator.onCandidateAvailable(late)
        assertEquals(1, late.releaseCount)
        assertEquals(1, outcomes.size)
    }

    @Test
    fun sequenceAbortAfterRegistrationReleasesPendingCandidateOnceAndRemainsTerminal() {
        val tagObj = Any()
        val outcomes = mutableListOf<CorrelationOutcome<TestCandidate>>()
        val correlator = createCorrelator(tagObj) { outcomes.add(it) }
        correlator.registerSequenceId(12)
        val pending = TestCandidate("pending", 1200L)
        correlator.onCandidateAvailable(pending)

        correlator.onCaptureSequenceAborted(sequenceId = 12)

        assertEquals(1, outcomes.size)
        assertTrue(outcomes.single() is CorrelationOutcome.Failure)
        assertEquals(1, pending.releaseCount)

        val late = TestCandidate("late", 1201L)
        correlator.onCandidateAvailable(late)
        assertEquals(1, late.releaseCount)
        assertEquals(1, outcomes.size)
    }

    @Test
    fun matchingSequenceCompletionRemainsNonterminalUntilLaterExactSuccess() {
        val tagObj = Any()
        var outcome: CorrelationOutcome<TestCandidate>? = null
        val correlator = createCorrelator(tagObj) { outcome = it }
        correlator.registerSequenceId(13)
        correlator.onCaptureSequenceCompleted(sequenceId = 13)
        assertNull(outcome)

        correlator.onCaptureCompleted(sequenceId = 13, sensorTimestamp = 1300L, tag = tagObj)
        val exact = TestCandidate("exact", 1300L)
        correlator.onCandidateAvailable(exact)

        assertTrue(outcome is CorrelationOutcome.Success)
        assertEquals(exact, (outcome as CorrelationOutcome.Success).candidate)
        assertEquals(0, exact.releaseCount)
    }

    @Test
    fun matchedCandidateTransfersWithoutCorrelatorReleaseIncludingAfterClose() {
        val tagObj = Any()
        var outcome: CorrelationOutcome<TestCandidate>? = null
        val correlator = createCorrelator(tagObj) { outcome = it }
        correlator.registerSequenceId(1)

        val c1 = TestCandidate("c1", 1000L)
        correlator.onCandidateAvailable(c1)
        correlator.onCaptureCompleted(sequenceId = 1, sensorTimestamp = 1000L, tag = tagObj)

        assertTrue(outcome is CorrelationOutcome.Success)
        val success = outcome as CorrelationOutcome.Success
        assertEquals(0, success.candidate.releaseCount)

        correlator.close()
        assertEquals(0, success.candidate.releaseCount)
    }

    @Test
    fun cancelledOldInstanceCallbacksCannotAffectNewInstance() {
        val tag1 = Any()
        var outcome1: CorrelationOutcome<TestCandidate>? = null
        val correlator1 = createCorrelator(tag1) { outcome1 = it }

        val tag2 = Any()
        var outcome2: CorrelationOutcome<TestCandidate>? = null
        val correlator2 = createCorrelator(tag2) { outcome2 = it }

        correlator1.close()

        val c1 = TestCandidate("c1", 1000L)
        correlator1.onCandidateAvailable(c1)
        assertEquals(1, c1.releaseCount)

        correlator2.registerSequenceId(2)
        val c2 = TestCandidate("c2", 2000L)
        correlator2.onCandidateAvailable(c2)
        correlator2.onCaptureCompleted(sequenceId = 2, sensorTimestamp = 2000L, tag = tag2)

        assertTrue(outcome2 is CorrelationOutcome.Success)
        assertEquals(c2, (outcome2 as CorrelationOutcome.Success).candidate)
        assertEquals(0, c2.releaseCount)
    }

    @Test
    fun candidateAcquisitionFailureReleasesPendingCandidates() {
        val tagObj = Any()
        var outcome: CorrelationOutcome<TestCandidate>? = null
        val correlator = createCorrelator(tagObj) { outcome = it }

        val c1 = TestCandidate("c1", 100L)
        correlator.onCandidateAvailable(c1)
        assertEquals(0, c1.releaseCount)

        correlator.onCandidateAcquisitionError(RuntimeException("Acquisition error"))

        assertTrue(outcome is CorrelationOutcome.Failure)
        assertEquals(1, c1.releaseCount)
    }

    @Test
    fun timestampExtractionFailureForPendingCandidateTriggersTerminalFailureAndReleasesAllOnce() {
        val tagObj = Any()
        var outcome: CorrelationOutcome<TestCandidate>? = null
        val correlator = createCorrelator(tagObj) { outcome = it }

        val c1 = TestCandidate("c1", 100L)
        correlator.onCandidateAvailable(c1)
        assertEquals(0, c1.releaseCount)

        val faulty = TestCandidate("faulty", 200L, throwOnExtract = true)
        correlator.onCandidateAvailable(faulty)

        assertTrue(outcome is CorrelationOutcome.Failure)
        val failure = outcome as CorrelationOutcome.Failure
        assertTrue(failure.cause.message?.contains("Extractor error") == true)

        assertEquals(1, c1.releaseCount)
        assertEquals(1, faulty.releaseCount)
    }

    @Test
    fun closeWithPendingCandidatesReleasesThemAndIsIdempotent() {
        val tagObj = Any()
        var outcome: CorrelationOutcome<TestCandidate>? = null
        val correlator = createCorrelator(tagObj) { outcome = it }

        val c1 = TestCandidate("c1", 100L)
        val c2 = TestCandidate("c2", 200L)
        correlator.onCandidateAvailable(c1)
        correlator.onCandidateAvailable(c2)

        correlator.close()

        assertTrue(outcome is CorrelationOutcome.Failure)
        assertEquals(1, c1.releaseCount)
        assertEquals(1, c2.releaseCount)

        correlator.close()
        assertEquals(1, c1.releaseCount)
        assertEquals(1, c2.releaseCount)
    }

    @Test
    fun lateCandidatesAfterSeparateSuccessFailureAndCancellationAreReleased() {
        // Late candidate after success
        val tag1 = Any()
        var outcome1: CorrelationOutcome<TestCandidate>? = null
        val correlator1 = createCorrelator(tag1) { outcome1 = it }
        correlator1.registerSequenceId(1)
        correlator1.onCaptureCompleted(sequenceId = 1, sensorTimestamp = 1000L, tag = tag1)
        val c1 = TestCandidate("c1", 1000L)
        correlator1.onCandidateAvailable(c1)
        assertTrue(outcome1 is CorrelationOutcome.Success)

        val late1 = TestCandidate("late1", 2000L)
        correlator1.onCandidateAvailable(late1)
        assertEquals(1, late1.releaseCount)

        // Late candidate after failure
        val tag2 = Any()
        var outcome2: CorrelationOutcome<TestCandidate>? = null
        val correlator2 = createCorrelator(tag2) { outcome2 = it }
        correlator2.onCaptureFailed(sequenceId = 2, tag = tag2)
        assertTrue(outcome2 is CorrelationOutcome.Failure)

        val late2 = TestCandidate("late2", 2000L)
        correlator2.onCandidateAvailable(late2)
        assertEquals(1, late2.releaseCount)

        // Late candidate after cancellation/close
        val tag3 = Any()
        var outcome3: CorrelationOutcome<TestCandidate>? = null
        val correlator3 = createCorrelator(tag3) { outcome3 = it }
        correlator3.close()
        assertTrue(outcome3 is CorrelationOutcome.Failure)

        val late3 = TestCandidate("late3", 2000L)
        correlator3.onCandidateAvailable(late3)
        assertEquals(1, late3.releaseCount)
    }

    @Test
    fun outcomeHandlerFailurePropagatesAfterOwnedCandidatesAreReleased() {
        val handlerFailure = IllegalStateException("Outcome handler failed")
        val outcomeCount = AtomicInteger(0)
        val tagObj = Any()
        val correlator = createCorrelator(tagObj) {
            outcomeCount.incrementAndGet()
            throw handlerFailure
        }
        val pending = TestCandidate("pending", 100L)
        correlator.onCandidateAvailable(pending)

        var propagated: Throwable? = null
        try {
            correlator.onCaptureBufferLost(tag = tagObj, frameNumber = 1L)
        } catch (failure: Throwable) {
            propagated = failure
        }

        assertTrue(propagated === handlerFailure)
        assertEquals(1, outcomeCount.get())
        assertEquals(1, pending.releaseCount)

        val late = TestCandidate("late", 101L)
        correlator.onCandidateAvailable(late)
        assertEquals(1, late.releaseCount)
        assertEquals(1, outcomeCount.get())
    }

    @Test
    fun concurrentTerminalPathsCannotDoubleReleaseAndAssertExactReleaseCount() {
        val outcomeCount = AtomicInteger(0)
        var lastOutcome: CorrelationOutcome<TestCandidate>? = null

        val tagObj = Any()
        val correlator = createCorrelator(tagObj) {
            outcomeCount.incrementAndGet()
            lastOutcome = it
        }
        correlator.registerSequenceId(1)

        val c1 = TestCandidate("c1", 1000L)

        val startBarrier = CyclicBarrier(3)
        val finished = CountDownLatch(3)
        val workerFailure = AtomicReference<Throwable?>(null)

        fun contendingThread(operation: () -> Unit): Thread {
            return Thread {
                try {
                    startBarrier.await(5, TimeUnit.SECONDS)
                    operation()
                } catch (failure: Throwable) {
                    workerFailure.compareAndSet(null, failure)
                } finally {
                    finished.countDown()
                }
            }
        }

        val thread1 = contendingThread {
            correlator.onCaptureCompleted(sequenceId = 1, sensorTimestamp = 1000L, tag = tagObj)
        }

        val thread2 = contendingThread {
            correlator.onCandidateAvailable(c1)
        }

        val thread3 = contendingThread {
            correlator.close()
        }

        thread1.start()
        thread2.start()
        thread3.start()

        assertTrue(finished.await(5, TimeUnit.SECONDS))
        thread1.join(1_000L)
        thread2.join(1_000L)
        thread3.join(1_000L)
        assertFalse(thread1.isAlive)
        assertFalse(thread2.isAlive)
        assertFalse(thread3.isAlive)
        assertNull(workerFailure.get())

        assertEquals(1, outcomeCount.get())
        assertNotNull(lastOutcome)
        if (lastOutcome is CorrelationOutcome.Success) {
            assertEquals(0, c1.releaseCount)
        } else {
            assertEquals(1, c1.releaseCount)
        }
    }
}
