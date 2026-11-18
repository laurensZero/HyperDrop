package cythen.hyperdrop.device.protocol

/**
 * GAIA command table for MOONDROP x Honkai: Star Rail "Robin's Earphones".
 *
 * Reverse-engineered from the official app over SPP/RFCOMM (Gaia v4 short frames):
 *
 *   TX  ff 04 00 00 00 1d 10 03          GET noise mode
 *   RX  ff 04 00 04 00 1d 11 03 00 01 00 00
 *   TX  ff 04 00 01 00 1d 10 04 <mode>   SET noise mode
 *   RX  ff 04 00 04 00 1d 1b 01 01 64 02 3c    battery: left=100%, right=60%
 *   TX  ff 04 00 00 00 1d 1e 01          GET gain
 *
 * Differences from PUDDING:
 *  - the audio-curation feature is 0x10 / 0x11, not 0x40 / 0x41;
 *  - the noise-mode wire values are powers of two: 1 = off, 2 = ANC, 4 = transparency;
 *  - there is no charging-case battery id (only 1 = left, 2 = right).
 */
object RobinCommandTable : GaiaCommandTable {

    override val sppUuid: String = GaiaTransport.SPP_UUID

    override val queryGaiaVersion: ByteArray = byteArrayOf(0x00, 0x0A, 0x03, 0x00)

    // AUDIO_CURATION feature 0x10: COMMAND 0x1000, RESPONSE 0x1100.
    override val queryNoiseMode: ByteArray = byteArrayOf(0x00, 0x1D, 0x10, 0x03)

    // DAC_GAIN feature 0x0F (same as PUDDING): COMMAND 0x1E00, RESPONSE 0x1F00.
    override val queryGainLevel: ByteArray = byteArrayOf(0x00, 0x1D, 0x1E, 0x01)

    // LED feature 0x13: not confirmed on this model, kept for interface parity.
    override val queryLedState: ByteArray = byteArrayOf(0x00, 0x1D, 0x26, 0x01)

    // BATTERY feature 0x0D: COMMAND 0x1A00, RESPONSE 0x1B00.
    override val querySupportedBatteries: ByteArray = byteArrayOf(0x00, 0x1D, 0x1A, 0x00)
    override val queryBatteryLevels: ByteArray = byteArrayOf(
        0x00, 0x1D, 0x1A, 0x01, 0x01, 0x02
    )

    override val ancCycle: List<Int> = listOf(2, 3, 1)

    /**
     * Robin encodes noise control as a bit flag, not a sequence:
     * 0x01 = off, 0x02 = noise cancellation, 0x04 = transparency.
     * [uiStatus] is the HyperOS status integer (see NoiseControlMode.toUiStatus()).
     */
    private enum class NoiseMode(val wireValue: Int, val uiStatus: Int) {
        OFF(0x01, 1),
        BASIC(0x02, 2),
        TRANSPARENCY(0x04, 3);

        companion object {
            fun fromWire(value: Int): NoiseMode? = entries.firstOrNull { it.wireValue == value }
            fun fromUiStatus(status: Int): NoiseMode? = entries.firstOrNull { it.uiStatus == status }
        }
    }

    // Gain wire direction is reversed, same as PUDDING: UI low/medium/high = wire 2/1/0.
    private enum class GainLevel(val uiValue: Int, val wireValue: Int) {
        LOW(0, 0x02),
        MEDIUM(1, 0x01),
        HIGH(2, 0x00);

        companion object {
            fun fromUi(value: Int): GainLevel? = entries.firstOrNull { it.uiValue == value }
            fun fromWire(value: Int): GainLevel? = entries.firstOrNull { it.wireValue == value }
        }
    }

    override fun setNoiseMode(uiStatus: Int): ByteArray? {
        val mode = NoiseMode.fromUiStatus(uiStatus) ?: return null
        return byteArrayOf(0x00, 0x1D, 0x10, 0x04, mode.wireValue.toByte())
    }

    override fun setGainLevel(uiValue: Int): ByteArray? {
        val gain = GainLevel.fromUi(uiValue) ?: return null
        return byteArrayOf(0x00, 0x1D, 0x1E, 0x02, gain.wireValue.toByte())
    }

    override fun setLedEnabled(enabled: Boolean): ByteArray = byteArrayOf(
        0x00, 0x1D, 0x26, 0x02, if (enabled) 0x01 else 0x00
    )

    override fun gainUiToWire(uiValue: Int): Int? = GainLevel.fromUi(uiValue)?.wireValue
    override fun gainWireToUi(wire: Int): Int? = GainLevel.fromWire(wire)?.uiValue
    override fun ancUiToWire(uiStatus: Int): Int? = NoiseMode.fromUiStatus(uiStatus)?.wireValue

    override fun parse(payload: ByteArray): GaiaReport {
        // GET response:  00 1D 11 03 00 <mode> 00 00
        // SET echo/notify: 00 1D 11 04 <mode>
        if (payload.size >= 6 && matches(payload, 0x11, 0x03)) {
            val mode = NoiseMode.fromWire(payload[5].toInt() and 0xFF)
            if (mode != null) return GaiaReport.AncMode(mode.uiStatus, mode.wireValue)
        }
        if (payload.size >= 5 && matches(payload, 0x11, 0x04)) {
            val mode = NoiseMode.fromWire(payload[4].toInt() and 0xFF)
            if (mode != null) return GaiaReport.AncMode(mode.uiStatus, mode.wireValue, isNotification = true)
        }

        // Battery: 00 1D 1B 01 (id, level)...
        if (payload.size >= 6 && matches(payload, 0x1B, 0x01)) {
            var left: Int? = null
            var right: Int? = null
            var case: Int? = null
            var i = 4
            while (i + 1 < payload.size) {
                val id = payload[i].toInt() and 0xFF
                val level = payload[i + 1].toInt() and 0xFF
                if (level in 0..100) {
                    when (id) {
                        1 -> left = level
                        2 -> right = level
                        3 -> case = level
                    }
                }
                i += 2
            }
            if (left != null || right != null || case != null) {
                return GaiaReport.Battery(left, right, case)
            }
        }

        // Gain GET response: 00 1D 1F 01 <wire>. The SET reply is `00 1D 1F 02 00`, a fixed
        // ack that carries no level — reading payload[4] of it would fake "gain = high".
        if (matchesAny(payload, 0x1F, 0x01)) {
            val wire = payload[4].toInt() and 0xFF
            val ui = GainLevel.fromWire(wire)?.uiValue
            if (ui != null) return GaiaReport.GainLevel(ui, wire)
        }

        // LED GET response: 00 1D 27 01 <0|1>; 27 02 is the fixed SET ack, not state.
        if (matchesAny(payload, 0x27, 0x01)) {
            val value = payload[4].toInt() and 0xFF
            if (value == 0 || value == 1) return GaiaReport.LedEnabled(value == 1)
        }

        if (payload.size >= 4 && payload[0] == 0x00.toByte() && payload[1] == 0x0A.toByte()) {
            return GaiaReport.GaiaNegotiation
        }

        return GaiaReport.Unknown
    }

    private fun matches(p: ByteArray, cmd: Int, sub: Int): Boolean =
        p.size >= 5 && p[0] == 0x00.toByte() && p[1] == 0x1D.toByte() &&
            p[2] == cmd.toByte() && p[3] == sub.toByte()

    private fun matchesAny(p: ByteArray, cmd: Int, vararg subs: Int): Boolean =
        p.size >= 5 && p[0] == 0x00.toByte() && p[1] == 0x1D.toByte() &&
            p[2] == cmd.toByte() && subs.any { p[3] == it.toByte() }
}
