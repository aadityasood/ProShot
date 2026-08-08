package com.proshot.app.camera

/**
 * Result outcome of timestamp correlation for a single still capture request.
 */
internal sealed class CorrelationOutcome<out T : Any> {
    /**
     * Exact timestamp match succeeded. Transfers ownership of [candidate] to caller.
     */
    data class Success<out T : Any>(
        val candidate: T,
        val timestamp: Long
    ) : CorrelationOutcome<T>()

    /**
     * Correlation encountered a terminal error or invalid state.
     */
    data class Failure(
        val cause: Throwable
    ) : CorrelationOutcome<Nothing>()
}

/**
 * Internal owned record wrapping a candidate and its extracted timestamp.
 */
private data class CandidateRecord<T : Any>(
    val candidate: T,
    val timestamp: Long
)

/**
 * Correlates Camera2 [android.hardware.camera2.TotalCaptureResult.SENSOR_TIMESTAMP] metadata
 * with image buffer candidates for a single shot.
 *
 * Enforces exact timestamp equality between result metadata and acquired frame buffers.
 * Holds at most three pre-result candidates to prevent reader exhaustion (capacity 4).
 *
 * @param requestTag Unique request identity object for reference-identity validation.
 * @param timestampExtractor Function extracting timestamp in nanoseconds from a candidate.
 * @param releaser Function releasing/closing an unmatched or stale candidate.
 * @param onOutcome Callback invoked at most once, outside [lock], when correlation reaches a
 * terminal outcome. It must complete normally; unexpected exceptions propagate to the callback
 * caller.
 */
internal class CaptureTimestampCorrelator<T : Any>(
    val requestTag: Any,
    private val timestampExtractor: (T) -> Long,
    private val releaser: (T) -> Unit,
    private val onOutcome: (CorrelationOutcome<T>) -> Unit
) : AutoCloseable {

    private val lock = Any()

    private var registeredSequenceId: Int? = null
    private var callbackSequenceId: Int? = null
    private var resultTimestamp: Long? = null

    private var pendingMatchedCandidate: CandidateRecord<T>? = null
    private val pendingCandidates = mutableListOf<CandidateRecord<T>>()

    private var isTerminal = false
    private var outcomeDispatched = false

    /**
     * Registers the authoritative sequence ID returned by [android.hardware.camera2.CameraCaptureSession.capture].
     */
    fun registerSequenceId(sequenceId: Int) {
        val candidatesToRelease = mutableListOf<T>()
        var pendingOutcome: CorrelationOutcome<T>? = null

        synchronized(lock) {
            if (isTerminal) return

            if (registeredSequenceId != null && registeredSequenceId != sequenceId) {
                pendingOutcome = CorrelationOutcome.Failure(
                    IllegalStateException("Conflicting sequence ID registration: $registeredSequenceId vs $sequenceId")
                )
                markTerminalLocked(candidatesToRelease)
            } else {
                registeredSequenceId = sequenceId
                callbackSequenceId?.let { cbSeq ->
                    if (cbSeq != sequenceId) {
                        pendingOutcome = CorrelationOutcome.Failure(
                            IllegalStateException("Sequence ID mismatch: registered $sequenceId but callback observed $cbSeq")
                        )
                        markTerminalLocked(candidatesToRelease)
                    }
                }

                if (!isTerminal && pendingMatchedCandidate != null) {
                    val matchedRecord = pendingMatchedCandidate!!
                    pendingMatchedCandidate = null
                    markTerminalLocked(candidatesToRelease)
                    pendingOutcome = CorrelationOutcome.Success(matchedRecord.candidate, matchedRecord.timestamp)
                }
            }
        }

        dispatchOutcomeAndRelease(pendingOutcome, candidatesToRelease)
    }

    /**
     * Processing hook when TotalCaptureResult is received for a request.
     */
    fun onCaptureCompleted(sequenceId: Int, sensorTimestamp: Long?, tag: Any?) {
        val candidatesToRelease = mutableListOf<T>()
        var pendingOutcome: CorrelationOutcome<T>? = null

        synchronized(lock) {
            if (isTerminal) return

            if (tag !== requestTag) {
                pendingOutcome = CorrelationOutcome.Failure(
                    IllegalArgumentException("Request tag mismatch: expected $requestTag but received $tag")
                )
                markTerminalLocked(candidatesToRelease)
                return@synchronized
            }

            if (!checkSequenceIdLocked(sequenceId)) {
                pendingOutcome = CorrelationOutcome.Failure(
                    IllegalStateException("Sequence ID mismatch on completion: expected $registeredSequenceId but received $sequenceId")
                )
                markTerminalLocked(candidatesToRelease)
                return@synchronized
            }

            if (sensorTimestamp == null) {
                pendingOutcome = CorrelationOutcome.Failure(
                    IllegalStateException("Null SENSOR_TIMESTAMP in TotalCaptureResult for request $requestTag")
                )
                markTerminalLocked(candidatesToRelease)
                return@synchronized
            }

            if (resultTimestamp != null) {
                pendingOutcome = CorrelationOutcome.Failure(
                    IllegalStateException("Duplicate/conflicting result timestamp for request $requestTag: existing $resultTimestamp vs new $sensorTimestamp")
                )
                markTerminalLocked(candidatesToRelease)
                return@synchronized
            }

            resultTimestamp = sensorTimestamp

            val matchIndex = pendingCandidates.indexOfFirst { record ->
                record.timestamp == sensorTimestamp
            }

            if (matchIndex != -1) {
                val matchedRecord = pendingCandidates.removeAt(matchIndex)
                candidatesToRelease.addAll(pendingCandidates.map { it.candidate })
                pendingCandidates.clear()

                if (registeredSequenceId != null) {
                    markTerminalLocked(candidatesToRelease)
                    pendingOutcome = CorrelationOutcome.Success(matchedRecord.candidate, sensorTimestamp)
                } else {
                    pendingMatchedCandidate = matchedRecord
                }
            } else {
                candidatesToRelease.addAll(pendingCandidates.map { it.candidate })
                pendingCandidates.clear()
            }
        }

        dispatchOutcomeAndRelease(pendingOutcome, candidatesToRelease)
    }

    /**
     * Processing hook when an image candidate buffer is acquired.
     */
    fun onCandidateAvailable(candidate: T) {
        val candTimestamp = try {
            timestampExtractor(candidate)
        } catch (e: Throwable) {
            failTerminalWithCandidate(candidate, e)
            return
        }

        val candidatesToRelease = mutableListOf<T>()
        var pendingOutcome: CorrelationOutcome<T>? = null

        synchronized(lock) {
            if (isTerminal) {
                candidatesToRelease.add(candidate)
                return@synchronized
            }

            val expectedTs = resultTimestamp

            if (expectedTs != null) {
                if (candTimestamp == expectedTs) {
                    if (registeredSequenceId != null) {
                        markTerminalLocked(candidatesToRelease)
                        pendingOutcome = CorrelationOutcome.Success(candidate, candTimestamp)
                    } else {
                        if (pendingMatchedCandidate == null) {
                            pendingMatchedCandidate = CandidateRecord(candidate, candTimestamp)
                        } else {
                            candidatesToRelease.add(candidate)
                        }
                    }
                } else {
                    candidatesToRelease.add(candidate)
                }
            } else {
                if (pendingCandidates.size >= 3) {
                    candidatesToRelease.add(candidate)
                    pendingOutcome = CorrelationOutcome.Failure(
                        IllegalStateException("Candidate retention limit reached (max 3) for request $requestTag")
                    )
                    markTerminalLocked(candidatesToRelease)
                } else {
                    pendingCandidates.add(CandidateRecord(candidate, candTimestamp))
                }
            }
        }

        dispatchOutcomeAndRelease(pendingOutcome, candidatesToRelease)
    }

    /**
     * Processing hook when sequence completed callback is received.
     * Validates sequence identity without failing prematurely while waiting for image/result.
     */
    fun onCaptureSequenceCompleted(sequenceId: Int) {
        val candidatesToRelease = mutableListOf<T>()
        var pendingOutcome: CorrelationOutcome<T>? = null

        synchronized(lock) {
            if (isTerminal) return

            if (!checkSequenceIdLocked(sequenceId)) {
                pendingOutcome = CorrelationOutcome.Failure(
                    IllegalStateException("Sequence ID mismatch on sequence completion: expected $registeredSequenceId but received $sequenceId")
                )
                markTerminalLocked(candidatesToRelease)
            }
        }

        dispatchOutcomeAndRelease(pendingOutcome, candidatesToRelease)
    }

    /**
     * Processing hook when capture fails.
     */
    fun onCaptureFailed(sequenceId: Int, tag: Any?) {
        val candidatesToRelease = mutableListOf<T>()
        var pendingOutcome: CorrelationOutcome<T>? = null

        synchronized(lock) {
            if (isTerminal) return

            if (tag !== requestTag) {
                pendingOutcome = CorrelationOutcome.Failure(
                    IllegalArgumentException("Request tag mismatch: expected $requestTag but received $tag")
                )
            } else if (!checkSequenceIdLocked(sequenceId)) {
                pendingOutcome = CorrelationOutcome.Failure(
                    IllegalStateException("Sequence ID mismatch on capture failure: expected $registeredSequenceId but received $sequenceId")
                )
            } else {
                pendingOutcome = CorrelationOutcome.Failure(
                    IllegalStateException("Capture failed for request $tag (sequence $sequenceId)")
                )
            }
            markTerminalLocked(candidatesToRelease)
        }

        dispatchOutcomeAndRelease(pendingOutcome, candidatesToRelease)
    }

    /**
     * Processing hook when YUV buffer is lost.
     */
    fun onCaptureBufferLost(tag: Any?, frameNumber: Long) {
        val candidatesToRelease = mutableListOf<T>()
        var pendingOutcome: CorrelationOutcome<T>? = null

        synchronized(lock) {
            if (isTerminal) return

            if (tag !== requestTag) {
                pendingOutcome = CorrelationOutcome.Failure(
                    IllegalArgumentException("Request tag mismatch: expected $requestTag but received $tag")
                )
            } else {
                pendingOutcome = CorrelationOutcome.Failure(
                    IllegalStateException("Capture buffer lost for request $tag (frame $frameNumber)")
                )
            }
            markTerminalLocked(candidatesToRelease)
        }

        dispatchOutcomeAndRelease(pendingOutcome, candidatesToRelease)
    }

    /**
     * Processing hook when capture sequence is aborted.
     */
    fun onCaptureSequenceAborted(sequenceId: Int) {
        val candidatesToRelease = mutableListOf<T>()
        var pendingOutcome: CorrelationOutcome<T>? = null

        synchronized(lock) {
            if (isTerminal) return

            if (registeredSequenceId != null && registeredSequenceId != sequenceId) {
                pendingOutcome = CorrelationOutcome.Failure(
                    IllegalStateException("Sequence ID mismatch on abort: expected $registeredSequenceId but received $sequenceId")
                )
            } else {
                pendingOutcome = CorrelationOutcome.Failure(
                    IllegalStateException("Capture sequence $sequenceId aborted for request $requestTag")
                )
            }
            markTerminalLocked(candidatesToRelease)
        }

        dispatchOutcomeAndRelease(pendingOutcome, candidatesToRelease)
    }

    /**
     * Processing hook on candidate acquisition error.
     */
    fun onCandidateAcquisitionError(cause: Throwable) {
        failTerminal(cause)
    }

    /**
     * Processing hook on submission failure or copy failure.
     */
    fun onSubmissionOrCopyFailed(cause: Throwable) {
        failTerminal(cause)
    }

    private fun failTerminalWithCandidate(candidate: T, cause: Throwable) {
        val candidatesToRelease = mutableListOf<T>()
        var pendingOutcome: CorrelationOutcome<T>? = null

        synchronized(lock) {
            if (isTerminal) {
                candidatesToRelease.add(candidate)
                return@synchronized
            }
            candidatesToRelease.add(candidate)
            pendingOutcome = CorrelationOutcome.Failure(cause)
            markTerminalLocked(candidatesToRelease)
        }

        dispatchOutcomeAndRelease(pendingOutcome, candidatesToRelease)
    }

    private fun failTerminal(cause: Throwable) {
        val candidatesToRelease = mutableListOf<T>()
        var pendingOutcome: CorrelationOutcome<T>? = null

        synchronized(lock) {
            if (isTerminal) return

            pendingOutcome = CorrelationOutcome.Failure(cause)
            markTerminalLocked(candidatesToRelease)
        }

        dispatchOutcomeAndRelease(pendingOutcome, candidatesToRelease)
    }

    override fun close() {
        val candidatesToRelease = mutableListOf<T>()
        var pendingOutcome: CorrelationOutcome<T>? = null

        synchronized(lock) {
            if (isTerminal) return

            pendingOutcome = CorrelationOutcome.Failure(
                IllegalStateException("Correlator closed before completion for request $requestTag")
            )
            markTerminalLocked(candidatesToRelease)
        }

        dispatchOutcomeAndRelease(pendingOutcome, candidatesToRelease)
    }

    private fun checkSequenceIdLocked(seqId: Int): Boolean {
        if (callbackSequenceId == null) {
            callbackSequenceId = seqId
        } else if (callbackSequenceId != seqId) {
            return false
        }
        val reg = registeredSequenceId
        return reg == null || reg == seqId
    }

    private fun markTerminalLocked(candidatesToRelease: MutableList<T>) {
        isTerminal = true
        pendingMatchedCandidate?.let { candidatesToRelease.add(it.candidate) }
        pendingMatchedCandidate = null
        candidatesToRelease.addAll(pendingCandidates.map { it.candidate })
        pendingCandidates.clear()
    }

    private fun dispatchOutcomeAndRelease(
        outcome: CorrelationOutcome<T>?,
        candidatesToRelease: List<T>
    ) {
        for (c in candidatesToRelease) {
            try {
                releaser(c)
            } catch (_: Throwable) {}
        }

        if (outcome != null) {
            val shouldDispatch: Boolean
            synchronized(lock) {
                if (!outcomeDispatched) {
                    outcomeDispatched = true
                    shouldDispatch = true
                } else {
                    shouldDispatch = false
                }
            }
            if (shouldDispatch) {
                onOutcome(outcome)
            }
        }
    }
}
