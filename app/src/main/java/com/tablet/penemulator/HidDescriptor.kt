package com.tablet.penemulator

/**
 * Standard HID Report Descriptor for an Absolute Pen Tablet / Digitizer.
 * Compatible with Windows Ink, macOS, Linux, and Android hosts.
 */
object HidDescriptor {

    const val REPORT_ID_PEN: Byte = 1

    /**
     * HID Descriptor defining a Digitizer -> Pen with:
     * - Tip Switch (drawing / contact)
     * - Barrel Switch (S-Pen button)
     * - Invert & Eraser
     * - In Range (S-Pen hover detection)
     * - X, Y absolute coordinates (0..32767)
     * - Tip Pressure (0..4095)
     * - Tilt X, Tilt Y (-90..+90 degrees)
     */
    val REPORT_DESCRIPTOR: ByteArray = byteArrayOf(
        0x05.toByte(), 0x0D.toByte(),        // Usage Page (Digitizer)
        0x09.toByte(), 0x02.toByte(),        // Usage (Pen)
        0xA1.toByte(), 0x01.toByte(),        // Collection (Application)
        0x85.toByte(), REPORT_ID_PEN,       //   Report ID (1)
        0x09.toByte(), 0x20.toByte(),        //   Usage (Stylus)
        0xA1.toByte(), 0x00.toByte(),        //   Collection (Physical)

        // Buttons & Status (1 byte total: 5 bits data + 3 bits padding)
        0x09.toByte(), 0x42.toByte(),        //     Usage (Tip Switch)
        0x09.toByte(), 0x44.toByte(),        //     Usage (Barrel Switch)
        0x09.toByte(), 0x3C.toByte(),        //     Usage (Invert)
        0x09.toByte(), 0x45.toByte(),        //     Usage (Eraser)
        0x09.toByte(), 0x32.toByte(),        //     Usage (In Range / Hover)
        0x15.toByte(), 0x00.toByte(),        //     Logical Minimum (0)
        0x25.toByte(), 0x01.toByte(),        //     Logical Maximum (1)
        0x75.toByte(), 0x01.toByte(),        //     Report Size (1 bit)
        0x95.toByte(), 0x05.toByte(),        //     Report Count (5)
        0x81.toByte(), 0x02.toByte(),        //     Input (Data,Var,Abs)

        0x95.toByte(), 0x03.toByte(),        //     Report Count (3 bits padding)
        0x81.toByte(), 0x03.toByte(),        //     Input (Const,Var,Abs)

        // X Coordinate (16-bit: 0..32767)
        0x05.toByte(), 0x01.toByte(),        //     Usage Page (Generic Desktop)
        0x09.toByte(), 0x30.toByte(),        //     Usage (X)
        0x15.toByte(), 0x00.toByte(),        //     Logical Minimum (0)
        0x26.toByte(), 0xFF.toByte(), 0x7F.toByte(), // Logical Maximum (32767)
        0x35.toByte(), 0x00.toByte(),        //     Physical Minimum (0)
        0x46.toByte(), 0xFF.toByte(), 0x7F.toByte(), // Physical Maximum (32767)
        0x65.toByte(), 0x11.toByte(),        //     Unit (SI Linear: cm)
        0x55.toByte(), 0x0E.toByte(),        //     Unit Exponent (-2)
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
        0x05.toByte(), 0x0D.toByte(),        //     Usage Page (Digitizer)
        0x09.toByte(), 0x30.toByte(),        //     Usage (Tip Pressure)
        0x15.toByte(), 0x00.toByte(),        //     Logical Minimum (0)
        0x26.toByte(), 0xFF.toByte(), 0x0F.toByte(), // Logical Maximum (4095)
        0x75.toByte(), 0x10.toByte(),        //     Report Size (16 bits)
        0x95.toByte(), 0x01.toByte(),        //     Report Count (1)
        0x81.toByte(), 0x02.toByte(),        //     Input (Data,Var,Abs)

        // X Tilt (-90..+90 degrees)
        0x09.toByte(), 0x3D.toByte(),        //     Usage (X Tilt)
        0x15.toByte(), 0xA6.toByte(),        //     Logical Minimum (-90)
        0x25.toByte(), 0x5A.toByte(),        //     Logical Maximum (90)
        0x75.toByte(), 0x08.toByte(),        //     Report Size (8 bits)
        0x95.toByte(), 0x01.toByte(),        //     Report Count (1)
        0x81.toByte(), 0x02.toByte(),        //     Input (Data,Var,Abs)

        // Y Tilt (-90..+90 degrees)
        0x09.toByte(), 0x3E.toByte(),        //     Usage (Y Tilt)
        0x15.toByte(), 0xA6.toByte(),        //     Logical Minimum (-90)
        0x25.toByte(), 0x5A.toByte(),        //     Logical Maximum (90)
        0x75.toByte(), 0x08.toByte(),        //     Report Size (8 bits)
        0x95.toByte(), 0x01.toByte(),        //     Report Count (1)
        0x81.toByte(), 0x02.toByte(),        //     Input (Data,Var,Abs)

        0xC0.toByte(),                      //   End Collection
        0xC0.toByte()                       // End Collection
    )

    /**
     * Builds the 9-byte report payload for a pen event.
     */
    fun buildReport(
        tipDown: Boolean,
        barrelButton: Boolean,
        inRange: Boolean,
        x: Int,         // 0..32767
        y: Int,         // 0..32767
        pressure: Int,  // 0..4095
        tiltX: Int,     // -90..90
        tiltY: Int      // -90..90
    ): ByteArray {
        val report = ByteArray(9)

        // Byte 0: Flags
        var flags = 0
        if (tipDown) flags = flags or 0x01
        if (barrelButton) flags = flags or 0x02
        // invert: 0x04, eraser: 0x08
        if (inRange) flags = flags or 0x10
        report[0] = flags.toByte()

        // Byte 1-2: X coordinate (Little Endian)
        val clampedX = x.coerceIn(0, 32767)
        report[1] = (clampedX and 0xFF).toByte()
        report[2] = ((clampedX shr 8) and 0xFF).toByte()

        // Byte 3-4: Y coordinate (Little Endian)
        val clampedY = y.coerceIn(0, 32767)
        report[3] = (clampedY and 0xFF).toByte()
        report[4] = ((clampedY shr 8) and 0xFF).toByte()

        // Byte 5-6: Pressure (Little Endian)
        val clampedPressure = if (tipDown) pressure.coerceIn(1, 4095) else 0
        report[5] = (clampedPressure and 0xFF).toByte()
        report[6] = ((clampedPressure shr 8) and 0xFF).toByte()

        // Byte 7: Tilt X (signed byte -90..90)
        report[7] = tiltX.coerceIn(-90, 90).toByte()

        // Byte 8: Tilt Y (signed byte -90..90)
        report[8] = tiltY.coerceIn(-90, 90).toByte()

        return report
    }
}
