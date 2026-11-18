package cythen.hyperdrop.device

import cythen.hyperdrop.config.ConfigManager
import cythen.hyperdrop.pods.AncImplementation

/**
 * Single source of truth for "which device is this, and what can it do".
 *
 * Matching is exact-model-first: the first registered profile whose [DeviceProfile.matches]
 * accepts the Bluetooth name wins. Anything else falls back to [StandardBluetoothProfile],
 * which claims no capabilities — an unknown device never silently gets PUDDING controls.
 *
 * Adding a MOONDROP model = one new [DeviceProfile] + one line in [registeredProfiles].
 */
object DeviceRegistry {

    /**
     * Registered real models. Order matters only if two profiles could match the same name.
     */
    val registeredProfiles: List<DeviceProfile> = listOf(
        PuddingProfile,
        RobinProfile,
    )

    /** Conservative fallback; never matched directly, only returned by [profileFor]. */
    val fallbackProfile: DeviceProfile = StandardBluetoothProfile

    fun profileFor(name: String?): DeviceProfile =
        registeredProfiles.firstOrNull { it.matches(name) } ?: fallbackProfile

    /** True when a concrete (non-fallback) profile owns this name. */
    fun isSupported(name: String?): Boolean =
        name != null && registeredProfiles.any { it.matches(name) }

    /** Lookup by stable id, for IPC payloads that carry a device id (phase F). */
    fun profileById(id: String): DeviceProfile =
        registeredProfiles.firstOrNull { it.id == id } ?: fallbackProfile

    /**
     * Capabilities for [deviceName] with per-feature user overrides applied on top of the
     * profile's auto-detected values.
     *
     * The signature intentionally mirrors the previous capability-detection entry point so
     * the existing call sites only need a `DeviceRegistry.` qualifier.
     */
    fun detectDeviceCapabilities(
        deviceName: String,
        adaptiveOverride: Int = ConfigManager.CAPABILITY_OVERRIDE_AUTO,
        spatialAudioOverride: Int = ConfigManager.CAPABILITY_OVERRIDE_AUTO,
        spatialSoundSwitchOverride: Int = ConfigManager.CAPABILITY_OVERRIDE_AUTO,
        ancImplementationOverride: Int = ConfigManager.CAPABILITY_OVERRIDE_AUTO,
    ): DeviceCapabilities {
        val base = profileFor(deviceName).capabilities
        return base.copy(
            adaptiveSupported = resolveCapability(adaptiveOverride, base.adaptiveSupported),
            spatialAudioSupported = resolveCapability(spatialAudioOverride, base.spatialAudioSupported),
            spatialSoundSwitchSupported = resolveCapability(spatialSoundSwitchOverride, base.spatialSoundSwitchSupported),
            ancImplementation = resolveAncImplementation(ancImplementationOverride, base.ancImplementation),
        )
    }

    private fun resolveCapability(override: Int, autoDetected: Boolean): Boolean = when (override) {
        ConfigManager.CAPABILITY_OVERRIDE_FORCE_ENABLED -> true
        ConfigManager.CAPABILITY_OVERRIDE_FORCE_DISABLED -> false
        else -> autoDetected
    }

    private fun resolveAncImplementation(
        override: Int,
        autoDetected: AncImplementation,
    ): AncImplementation = when (override) {
        ConfigManager.CAPABILITY_OVERRIDE_FORCE_ENABLED -> AncImplementation.COMPATIBLE
        ConfigManager.CAPABILITY_OVERRIDE_FORCE_DISABLED -> AncImplementation.STANDARD
        else -> autoDetected
    }
}
