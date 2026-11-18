package cythen.hyperdrop.ui

import android.os.SystemClock
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import cythen.hyperdrop.pods.NoiseControlMode
import cythen.hyperdrop.pods.ancModeFromUiStatus
import cythen.hyperdrop.pods.toUiStatus

/**
 * Shared ANC state for the app process. Bluetooth owns the wire state; every app surface
 * consumes the same broadcast stream and uses this coordinator to reject stale reports.
 */
object AncUiState {
    val mode: MutableState<NoiseControlMode> = mutableStateOf(NoiseControlMode.OFF)

    private var pendingStatus: Int? = null
    private var pendingUntilMs = 0L

    fun select(mode: NoiseControlMode) {
        this.mode.value = mode
        pendingStatus = mode.toUiStatus()
        pendingUntilMs = SystemClock.elapsedRealtime() + 8_000L
    }

    fun acceptStatus(status: Int): Boolean {
        val pending = pendingStatus
        if (pending != null &&
            SystemClock.elapsedRealtime() < pendingUntilMs &&
            status != pending
        ) {
            return false
        }
        mode.value = ancModeFromUiStatus(status)
        return true
    }

    fun clear() {
        pendingStatus = null
        pendingUntilMs = 0L
        mode.value = NoiseControlMode.OFF
    }
}
