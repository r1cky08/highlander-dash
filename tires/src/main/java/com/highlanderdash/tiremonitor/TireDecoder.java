package com.highlanderdash.tiremonitor;

/**
 * Decodes the second tire sensor set (broadcast only, no pairing): service 0xFBB0, manufacturer data under
 * company id 0x00AC (a placeholder id). The 15 bytes after the company id are:
 *   0 battery voltage (0.01 V steps: volts = (byte + 122) / 100; 0xB2 = 3.00 V, the top the sensor reports; confirmed against
 *   the sensors' own app on Nov 22, 2026 at 2.76, 2.79 and 3.00 V), 1 pressure step count, 2 temperature (whole degrees C plus 55), 3 constant 0,
 *   4 constant 0x30, 5 unknown (drifts slowly, differs per sensor), 6 constant 0x41,
 *   7 checksum (bytes 0 to 7 add up to a per-sensor constant), 8 constant 0x28,
 *   9-14 the sensor's own Bluetooth address, reversed.
 * Pressure is about 0.46 psi per step: psi = step x 13/28 - 0.5. The formula was fitted on six captures between
 * 26.9 and 28.3 psi (see claude/new-tpms-sensors.md in the project), so treat it as provisional until it has been
 * checked at a low and a high pressure.
 * Plain Java (no Android classes) so it can be tested off the phone.
 */
final class TireDecoder {
    static final int COMPANY = 0x00AC;
    static final double PSI_PER_STEP = 13.0 / 28.0;
    static final double PSI_OFFSET = -0.5;
    static final int TEMP_OFFSET_C = 55;
    /** At or below this the sensors' own app calls the battery Low (2.74 V Low, 2.76 V Medium). */
    static final double LOW_VOLTS = 2.75;
    private static final double PA_PER_PSI = 6894.757;

    static final class Reading {
        final String mac;   // upper case, colon separated, same form Android prints for the device
        final double psi;   // gauge pressure, never below 0
        final int tempC;
        final long pa;      // the same pressure in pascals, the unit the page already expects
        final double volts; // sensor battery voltage
        Reading(String mac, double psi, int tempC, double volts) {
            this.volts = volts;
            this.mac = mac;
            this.psi = psi;
            this.tempC = tempC;
            this.pa = Math.round(psi * PA_PER_PSI);
        }
    }

    /** @param d the manufacturer data after the company id; null or too short gives null. */
    static Reading decode(byte[] d) {
        if (d == null || d.length < 15) return null;
        StringBuilder mac = new StringBuilder();
        for (int i = 14; i >= 9; i--) {
            if (mac.length() > 0) mac.append(':');
            int v = d[i] & 0xff;
            mac.append(Character.toUpperCase(Character.forDigit(v >> 4, 16))).append(Character.toUpperCase(Character.forDigit(v & 15, 16)));
        }
        double psi = (d[1] & 0xff) * PSI_PER_STEP + PSI_OFFSET;
        if (psi < 0) psi = 0;
        return new Reading(mac.toString(), psi, (d[2] & 0xff) - TEMP_OFFSET_C, ((d[0] & 0xff) + 122) / 100.0);
    }
}
