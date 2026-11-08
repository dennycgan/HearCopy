package com.dennycgan.hearcopy.capture

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.fail

/**
 * Scripted [AudioSource]. [read] returns queued steps in order; with an empty
 * queue it blocks until a step is queued, until [stop] (unless
 * [ignoreStop]), or until [finishBlockedRead] / [shutdown].
 */
class FakeAudioSource(
    private val ignoreStop: Boolean = false,
    /** What a read unblocked by stop returns. */
    private val returnAfterStop: Int = 0,
) : AudioSource {

    sealed interface Step {
        data class Samples(val count: Int, val amplitude: Short) : Step
        data class Code(val value: Int) : Step
    }

    /** Ordered call log: start, read, returned, stop, release. */
    val calls = CopyOnWriteArrayList<String>()
    val releaseThreads = CopyOnWriteArrayList<String>()
    val readTimesNs = CopyOnWriteArrayList<Long>()
    val stopCount = AtomicInteger()
    val releaseCount = AtomicInteger()

    @Volatile var startError: Exception? = null

    /** When set and the queue is empty, every read returns this many samples. */
    @Volatile var endless: Step.Samples? = null

    private val lock = Any()
    private val steps = ArrayDeque<Step>()
    private var enqueued = 0
    private var idleAt = -1

    @Volatile private var stopped = false
    @Volatile private var forced = false
    private val released = CountDownLatch(1)

    fun enqueue(vararg step: Step) = synchronized(lock) {
        step.forEach(steps::addLast)
        enqueued += step.size
    }

    fun samples(chunks: Int, amplitude: Short, count: Int = CaptureSession.CHUNK_SAMPLES) =
        enqueue(*Array(chunks) { Step.Samples(count, amplitude) })

    override fun start() {
        calls += "start"
        startError?.let { throw it }
    }

    override fun read(buffer: ShortArray, offset: Int, size: Int): Int {
        calls += "read"
        readTimesNs += System.nanoTime()
        while (true) {
            // Polling and marking idle under one lock: idle means every queued
            // step was returned and the reader came back for more.
            val step = synchronized(lock) {
                steps.removeFirstOrNull() ?: endless.also { if (it == null) idleAt = enqueued }
            }
            if (step != null) return apply(step, buffer, offset, size).also { calls += "returned" }
            if (forced || (stopped && !ignoreStop)) return returnAfterStop.also { calls += "returned" }
            Thread.sleep(1)
        }
    }

    /** Runs at the start of [stop], before the read is unblocked. */
    @Volatile var onStop: (() -> Unit)? = null

    override fun stop() {
        onStop?.invoke()
        calls += "stop"
        stopCount.incrementAndGet()
        stopped = true
    }

    override fun release() {
        calls += "release"
        releaseThreads += Thread.currentThread().name
        releaseCount.incrementAndGet()
        released.countDown()
    }

    /** Unblocks a read that ignores stop. */
    fun finishBlockedRead() {
        forced = true
    }

    /** Test cleanup: unblocks everything. */
    fun shutdown() {
        forced = true
        stopped = true
    }

    /** Blocks until the reader has consumed every queued step and waits in read. */
    fun awaitIdle() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(GUARD_SECONDS)
        while (synchronized(lock) { idleAt != enqueued }) {
            if (System.nanoTime() > deadline) fail("reader did not become idle")
            Thread.sleep(1)
        }
    }

    fun awaitReleased() {
        if (!released.await(GUARD_SECONDS, TimeUnit.SECONDS)) fail("source was not released")
    }

    private fun apply(step: Step, buffer: ShortArray, offset: Int, size: Int): Int = when (step) {
        is Step.Code -> step.value
        is Step.Samples -> {
            val n = minOf(step.count, size)
            buffer.fill(step.amplitude, offset, offset + n)
            n
        }
    }

    companion object {
        /** Real-time guard for latches; never part of the asserted timing. */
        const val GUARD_SECONDS = 10L
    }
}
