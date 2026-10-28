package com.tablet.penemulator

/**
 * Standard HID Report Descriptor for an Absolute External Digitizer Tablet.
 * Configured as an External Digitizer (Usage Page 0x0D, Usage 0x01) so Windows Ink
 * automatically routes absolute coordinates across the primary monitor and virtual desktop
 * without requiring an integrated monitor ACPI mapping.
 */
object HidDescriptor {

    const val REPORT_ID_PEN: Byte = 1

    /**
     * HID Descriptor defining an External Digitizer Tablet with:
     * - Tip Switch (bit 0: drawing / contact)
     * - In Range (bit 1: S-Pen hover detection)
     * - Barrel Switch (bit 2: S-Pen side button)
     * - X, Y absolute coordinates (0..32767)
     * - Tip Pressure (0..4095)
     */
    val REPORT_DESCRIPTOR: ByteArray = byteArrayOf(
        0x05.toByte(), 0x0D.toByte(),        // Usage Page (Digitizers)
        0x09.toByte(), 0x01.toByte(),        // Usage (Digitizer) -> External Pen Tablet
        0xA1.toByte(), 0x01.toByte(),        // Collection (Application)
        0x85.toByte(), REPORT_ID_PEN,       //   Report ID (1)
        0x09.toByte(), 0x20.toByte(),        //   Usage (Stylus)
        0xA1.toByte(), 0x00.toByte(),        //   Collection (Physical)

        // Buttons & Status (1 byte: 3 bits data + 5 bits padding)
        0x05.toByte(), 0x0D.toByte(),        //     Usage Page (Digitizers)
        0x09.toByte(), 0x42.toByte(),        //     Usage (Tip Switch)    -> bit 0
        0x09.toByte(), 0x32.toByte(),        //     Usage (In Range)      -> bit 1
        0x09.toByte(), 0x44.toByte(),        //     Usage (Barrel Switch) -> bit 2
        0x15.toByte(), 0x00.toByte(),        //     Logical Minimum (0)
        0x25.toByte(), 0x01.toByte(),        //     Logical Maximum (1)
        0x75.toByte(), 0x01.toByte(),        //     Report Size (1 bit)
        0x95.toByte(), 0x03.toByte(),        //     Report Count (3)
        0x81.toByte(), 0x02.toByte(),        //     Input (Data,Var,Abs)

        0x95.toByte(), 0x05.toByte(),        //     Report Count (5 bits padding to complete byte)
        0x81.toByte(), 0x03.toByte(),        //     Input (Const,Var,Abs)

        // X Coordinate (16-bit: 0..32767)
        0x05.toByte(), 0x01.toByte(),        //     Usage Page (Generic Desktop)
        0x09.toByte(), 0x30.toByte(),        //     Usage (X)
        0x15.toByte(), 0x00.toByte(),        //     Logical Minimum (0)
        0x26.toByte(), 0xFF.toByte(), 0x7F.toByte(), // Logical Maximum (32767)
        0x35.toByte(), 0x00.toByte(),        //     Physical Minimum (0)
        0x46.toByte(), 0xFF.toByte(), 0x7F.toByte(), // Physical Maximum (32767)
        0x75.toByte(), 0x10.toByte(),        //     Report Size (16 bits)
        0x95.toByte(), 0x01.toByte(),        //     Report Count (1)
        0x81.toByte(), 0x02.toByte(),        //     Input (Data,Var,Abs)

        // Y Coordinate (16-bit: 0..32767)
        0x09.toByte(), 0x31.toByte(),        //     Usage (Y)
        0x15.toByte(), 0x00.toByte(),        //     Logical Minimum (0)
        0x26.toByte(), 0xFF.toByte(), 0x7F.toByte(), // Logical Maximum (32767)
        0x35.toByte(), 0x00.toByte(),        //     Physical Minimum (0)
        0x46.toByte(), 0xFF.toByte(), 0x7F.toByte(), // Physical Maximum (32767)
        0x75.toByte(), 0x10.toByte(),        //     Report Size (16 bits)
        0x95.toByte(), 0x01.toByte(),        //     Report Count (1)
        0x81.toByte(), 0x02.toByte(),        //     Input (Data,Var,Abs)

        // Tip Pressure (16-bit: 0..4095)
        0x05.toByte(), 0x0D.toByte(),        //     Usage Page (Digitizers)
        0x09.toByte(), 0x30.toByte(),        //     Usage (Tip Pressure)
        0x15.toByte(), 0x00.toByte(),        //     Logical Minimum (0)
        0x26.toByte(), 0xFF.toByte(), 0x0F.toByte(), // Logical Maximum (4095)
        0x35.toByte(), 0x00.toByte(),        //     Physical Minimum (0)
        0x46.toByte(), 0xFF.toByte(), 0x0F.toByte(), // Physical Maximum (4095)
        0x75.toByte(), 0x10.toByte(),        //     Report Size (16 bits)
        0x95.toByte(), 0x01.toByte(),        //     Report Count (1)
        0x81.toByte(), 0x02.toByte(),        //     Input (Data,Var,Abs)

        0xC0.toByte(),                      //   End Collection
        0xC0.toByte()                       // End Collection
    )

    /**
     * Builds the standard 7-byte report payload for an external pen digitizer event.
     */
    fun buildReport(
        tipDown: Boolean,
        barrelButton: Boolean,
        inRange: Boolean,
        x: Int,         // 0..32767
        y: Int,         // 0..32767
        pressure: Int   // 0..4095
    ): ByteArray {
        val report = ByteArray(7)

        // Byte 0: Flags
        var flags = 0
        if (tipDown) flags = flags or 0x01      // bit 0 = Tip Switch
        if (inRange) flags = flags or 0x02      // bit 1 = In Range
        if (barrelButton) flags = flags or 0x04 // bit 2 = Barrel Switch
        report[0] = flags.toByte()

        // Byte 1-2: X coordinate (Little Endian, 0..32767)
        val clampedX = x.coerceIn(0, 32767)
        report[1] = (clampedX and 0xFF).toByte()
        report[2] = ((clampedX shr 8) and 0xFF).toByte()

        // Byte 3-4: Y coordinate (Little Endian, 0..32767)
        val clampedY = y.coerceIn(0, 32767)
        report[3] = (clampedY and 0xFF).toByte()
        report[4] = ((clampedY shr 8) and 0xFF).toByte()

        // Byte 5-6: Pressure (Little Endian, 0..4095)
        val clampedPressure = if (tipDown) pressure.coerceIn(1, 4095) else 0
        report[5] = (clampedPressure and 0xFF).toByte()
        report[6] = ((clampedPressure shr 8) and 0xFF).toByte()

        return report
    }
}
