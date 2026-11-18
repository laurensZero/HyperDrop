package cythen.hyperdrop.device

import cythen.hyperdrop.device.protocol.GaiaReport
import cythen.hyperdrop.device.protocol.RobinCommandTable
import cythen.hyperdrop.pods.NoiseControlMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire expectations below come from the SPP capture in the reverse-engineering notes:
 * GET noise mode replies `00 1D 11 03 00 <mode> 00 00`, battery replies
 * `00 1D 1B 01 01 64 02 3c`, gain replies `00 1D 1F 01 <wire>`.
 */
class RobinProfileTest {
    @Test
    fun `robin profile owns its model names`() {
        assertTrue(RobinProfile.matches("Robin's Earphones"))
        assertTrue(RobinProfile.matches("Robin’s Earphones"))
        assertTrue(RobinProfile.matches("robin"))
        assertTrue(RobinProfile.matches("知更鸟"))
        assertFalse(RobinProfile.matches("MOONDROP PUDDING"))
        assertFalse(RobinProfile.matches(null))
    }

    @Test
    fun `registry reports robin as supported`() {
        assertTrue(DeviceRegistry.isSupported("Robin's Earphones"))
        assertEquals(RobinProfile, DeviceRegistry.profileFor("Robin's Earphones"))
        assertEquals("robin", DeviceRegistry.profileById("robin").id)
    }

    @Test
    fun `robin has no anc strengths but three quick modes`() {
        val caps = DeviceRegistry.detectDeviceCapabilities("Robin's Earphones")
        assertFalse(caps.adaptiveSupported)
        assertTrue(caps.ancModes.isEmpty())
        assertEquals(3, caps.quickModes.size)
        assertEquals(NoiseControlMode.OFF, caps.quickModes[0].mode)
        assertEquals(NoiseControlMode.NOISE_CANCELLATION, caps.quickModes[1].mode)
        assertEquals(NoiseControlMode.TRANSPARENCY, caps.quickModes[2].mode)
    }

    @Test
    fun `noise mode encodes as a bit flag`() {
        assertArrayEquals(
            byteArrayOf(0x00, 0x1D, 0x10, 0x04, 0x01),
            RobinCommandTable.setNoiseMode(1),
        )
        assertArrayEquals(
            byteArrayOf(0x00, 0x1D, 0x10, 0x04, 0x02),
            RobinCommandTable.setNoiseMode(2),
        )
        assertArrayEquals(
            byteArrayOf(0x00, 0x1D, 0x10, 0x04, 0x04),
            RobinCommandTable.setNoiseMode(3),
        )
        // Adaptive / wind reduction do not exist on this model.
        assertNull(RobinCommandTable.setNoiseMode(4))
        assertNull(RobinCommandTable.setNoiseMode(5))
    }

    @Test
    fun `gain keeps the reversed pudding encoding`() {
        assertEquals(2, RobinCommandTable.gainUiToWire(0))
        assertEquals(1, RobinCommandTable.gainUiToWire(1))
        assertEquals(0, RobinCommandTable.gainUiToWire(2))
        assertEquals(1, RobinCommandTable.gainWireToUi(0x01))
        assertNull(RobinCommandTable.gainWireToUi(0x03))
    }

    @Test
    fun `parses noise mode response`() {
        val off = RobinCommandTable.parse(
            byteArrayOf(0x00, 0x1D, 0x11, 0x03, 0x00, 0x01, 0x00, 0x00),
        )
        assertEquals(GaiaReport.AncMode(1, 1), off)

        val anc = RobinCommandTable.parse(
            byteArrayOf(0x00, 0x1D, 0x11, 0x03, 0x00, 0x02, 0x00, 0x00),
        )
        assertEquals(GaiaReport.AncMode(2, 2), anc)

        val transparency = RobinCommandTable.parse(
            byteArrayOf(0x00, 0x1D, 0x11, 0x03, 0x00, 0x04, 0x00, 0x00),
        )
        assertEquals(GaiaReport.AncMode(3, 4), transparency)

        // SET echo carries the mode at offset 4.
        assertEquals(
            GaiaReport.AncMode(3, 4, isNotification = true),
            RobinCommandTable.parse(byteArrayOf(0x00, 0x1D, 0x11, 0x04, 0x04)),
        )
    }

    @Test
    fun `parses left and right battery without a case`() {
        val report = RobinCommandTable.parse(
            byteArrayOf(0x00, 0x1D, 0x1B, 0x01, 0x01, 0x64, 0x02, 0x3C),
        )
        assertEquals(GaiaReport.Battery(left = 100, right = 60, case = null), report)
    }

    @Test
    fun `parses gain response and ignores unknown payloads`() {
        assertEquals(
            GaiaReport.GainLevel(uiValue = 1, wire = 0x01),
            RobinCommandTable.parse(byteArrayOf(0x00, 0x1D, 0x1F, 0x01, 0x01)),
        )
        assertEquals(
            GaiaReport.Unknown,
            RobinCommandTable.parse(byteArrayOf(0x00, 0x1D, 0x7E, 0x01, 0x00)),
        )
    }

    @Test
    fun `set replies are fixed acks and carry no state`() {
        // Captured from the official app: SET gain 0x02/0x01/0x00 always answers 1f 02 00.
        assertEquals(
            GaiaReport.Unknown,
            RobinCommandTable.parse(byteArrayOf(0x00, 0x1D, 0x1F, 0x02, 0x00)),
        )
        assertEquals(
            GaiaReport.Unknown,
            RobinCommandTable.parse(byteArrayOf(0x00, 0x1D, 0x27, 0x02, 0x00)),
        )
    }

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray?) {
        org.junit.Assert.assertArrayEquals(expected, actual)
    }
}
