package cythen.hyperdrop.device

import cythen.hyperdrop.R
import cythen.hyperdrop.device.protocol.RobinCommandTable
import cythen.hyperdrop.pods.AncImplementation
import cythen.hyperdrop.pods.NoiseControlMode

/**
 * MOONDROP x Honkai: Star Rail "Robin's Earphones" (Bluetrum BT895x, codename QUEN,
 * firmware 1.3.5 at the time of writing).
 *
 * The earbuds speak Qualcomm GAIA v4 over the standard SPP UUID, so the transport and
 * hook layers are reused as-is; only the command vocabulary differs. See
 * [RobinCommandTable] for the captured wire format.
 *
 * Noise control exposes three modes only: off / noise cancellation / transparency.
 * There is no adaptive or wind-noise mode, and no charging-case battery.
 */
object RobinProfile : DeviceProfile {
    override val id: String = "robin"
    override val displayName: String = "MOONDROP Robin's Earphones"
    override val defaultImageRes: Int = R.drawable.robin_earphones

    // Official per-ear artwork, cropped from the transparent CDN "双耳" asset.
    override val defaultLeftImageRes: Int = R.drawable.robin_left
    override val defaultRightImageRes: Int = R.drawable.robin_right

    override fun matches(name: String?): Boolean {
        val normalized = name.orEmpty().lowercase()
            .replace("-", "").replace("_", "").replace(" ", "")
        return normalized.contains("robin") || normalized.contains("知更鸟")
    }

    override val capabilities: DeviceCapabilities = DeviceCapabilities(
        adaptiveSupported = false,
        spatialAudioSupported = false,
        spatialSoundSwitchSupported = false,
        ancImplementation = AncImplementation.STANDARD,
        // No ANC strength flavours: the three system buttons already cover
        // off / noise cancellation / transparency.
        ancModes = emptyList(),
        quickModes = listOf(
            AncModeOption(NoiseControlMode.OFF, R.string.off),
            AncModeOption(NoiseControlMode.NOISE_CANCELLATION, R.string.noise_cancellation_title),
            AncModeOption(NoiseControlMode.TRANSPARENCY, R.string.transparency_title),
        ),
    )

    override val commandTable = RobinCommandTable
}
