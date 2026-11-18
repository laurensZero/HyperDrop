package cythen.hyperdrop.device

import cythen.hyperdrop.R
import cythen.hyperdrop.device.protocol.GaiaCommandTable

/**
 * One supported device is one [DeviceProfile]. Everything that varies between models is a
 * property of the profile; the rest of the app talks to the abstract type.
 *
 * Adding a new MOONDROP model = one new [DeviceProfile] implementation + a
 * [GaiaCommandTable] for its wire protocol + one line in
 * [DeviceRegistry.registeredProfiles]. No change needed in pods/, hook/ or ui/.
 */
interface DeviceProfile {
    /** Stable identifier, e.g. "pudding". Used in IPC payloads from phase F. */
    val id: String

    /** Human-readable name shown in device pickers / debug UI. */
    val displayName: String

    /** True when this profile owns the given Bluetooth name/alias. */
    fun matches(name: String?): Boolean

    /** Auto-detected capabilities for this model, before user overrides. */
    val capabilities: DeviceCapabilities

    /** Byte-level command vocabulary for this model's GAIA flavour. */
    val commandTable: GaiaCommandTable

    /** Bundled product artwork used when the user has not selected a custom image. */
    val defaultImageRes: Int
        get() = R.drawable.img_box

    /** Bundled left-ear artwork for the focus island's left slot; defaults to [defaultImageRes]. */
    val defaultLeftImageRes: Int
        get() = defaultImageRes

    /** Bundled right-ear artwork for the focus island's right slot; defaults to [defaultImageRes]. */
    val defaultRightImageRes: Int
        get() = defaultImageRes
}
