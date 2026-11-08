package com.dennycgan.hearcopy.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet

/**
 * Application-wide single source of capture state. Constructed only in
 * AppContainer; only capture/ dispatches events.
 */
class CaptureStateStore(initial: CaptureState = CaptureState()) {

    private val mutableState = MutableStateFlow(initial)

    val state: StateFlow<CaptureState> = mutableState.asStateFlow()

    /** Applies [event] atomically and returns the resulting state. */
    fun dispatch(event: CaptureEvent): CaptureState =
        mutableState.updateAndGet { reduce(it, event) }
}
