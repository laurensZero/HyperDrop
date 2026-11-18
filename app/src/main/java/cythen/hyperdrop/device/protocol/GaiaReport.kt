package cythen.hyperdrop.device.protocol

/**
 * Typed interpretation of one received GAIA payload.
 *
 * A [CommandTable] turns model-specific response bytes into one of these reports; the
 * session layer only ever sees semantics, never raw vendor bytes.
 */
sealed interface GaiaReport {
    /** Noise-control mode changed (or was reported). [uiStatus] is the HyperOS status index. */
    data class AncMode(
        val uiStatus: Int,
        val wire: Int,
        /** True for an unsolicited/set response rather than a GET response. */
        val isNotification: Boolean = false,
    ) : GaiaReport

    /** Battery levels, each null when that component was not reported / out of range. */
    data class Battery(val left: Int?, val right: Int?, val case: Int?) : GaiaReport

    data class GainLevel(val uiValue: Int, val wire: Int) : GaiaReport

    data class LedEnabled(val enabled: Boolean) : GaiaReport

    /** GAIA v1 API-version response; negotiation only, no state to publish. */
    object GaiaNegotiation : GaiaReport

    /** Payload that this command table does not recognise. */
    object Unknown : GaiaReport
}
