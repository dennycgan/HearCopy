package com.dennycgan.hearcopy.state

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureStateStoreTest {

    @Test
    fun `dispatch applies reduce and returns the new state`() {
        val store = CaptureStateStore()
        store.dispatch(CaptureEvent.SessionStarted)
        val started = store.dispatch(CaptureEvent.TapStart)

        assertEquals(CaptureStatus.Preparing, started.status)
        assertEquals(1, started.attempt)
        assertEquals(started, store.state.value)
    }

    @Test
    fun `an event that does not apply leaves the state unchanged`() {
        val store = CaptureStateStore()
        val before = store.state.value
        assertEquals(before, store.dispatch(CaptureEvent.TapStop))
        assertEquals(before, store.state.value)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `collectors see each distinct state`() = runTest {
        val store = CaptureStateStore()
        val seen = mutableListOf<CaptureState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { store.state.toList(seen) }

        store.dispatch(CaptureEvent.SessionStarted)
        store.dispatch(CaptureEvent.TapStop) // no change, no emission
        store.dispatch(CaptureEvent.TapStart)

        assertEquals(
            listOf(CaptureStatus.Ready, CaptureStatus.Ready, CaptureStatus.Preparing),
            seen.map { it.status },
        )
        assertEquals(listOf(false, true, true), seen.map { it.setup.projectionActive })
    }
}
