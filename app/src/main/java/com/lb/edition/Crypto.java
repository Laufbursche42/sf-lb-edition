// Laufbursche Edition - an app for SoFlow e-scooters.
// Copyright (c) 2026 Laufbursche (https://github.com/Laufbursche42)
// Source-available under the PolyForm Noncommercial License 1.0.0 with Additional Terms. See license.md.

package com.lb.edition;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-128-ECB with zero padding and two hardcoded keys, exactly as the SoFlow controller firmware
 * expects on the wire (verified against the spec 3.2 test vectors in selfTest() below) - not a
 * choice made here. A different mode/key would not talk to the real hardware; the keys are already
 * public (extracted from the manufacturer's own app), and ECB's known weakness (repeated plaintext
 * blocks leak a pattern) is not a real exposure for these single 7-16 byte command frames. SpotBugs'
 * ECB_MODE/CIPHER_INTEGRITY findings on this class are protocol interop, not fixable without
 * breaking scooter communication.
 */
final class Crypto {

    private Crypto() {}

    // Static command keys, hard-coded in the app (spec 3.1).
    static final byte[] KEY_A = hexToBytes("30572F52364B3F473050415811632D2B");   // D7 family
    static final byte[] KEY_B = hexToBytes("20572F52364B3F473050415811632D2B");   // SO6 family

    /** True once the class-load self-test against the spec 3.2 vectors passed. */
    static final boolean OK = selfTest();

    /** Encrypt with zero padding to the next 16-byte multiple, AES-128-ECB. */
    static byte[] encrypt(byte[] data, byte[] key) {
        int pad = (16 - (data.length % 16)) % 16;
        byte[] buf = new byte[data.length + pad];
        System.arraycopy(data, 0, buf, 0, data.length);
        try {
            Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
            return c.doFinal(buf);
        } catch (Exception e) {
            return null;
        }
    }

    /** Decrypt only whole 16-byte blocks; a trailing partial block is ignored. */
    static byte[] decrypt(byte[] data, byte[] key) {
        int n = data.length - (data.length % 16);
        if (n <= 0) return new byte[0];
        byte[] block = new byte[n];
        System.arraycopy(data, 0, block, 0, n);
        try {
            Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
            return c.doFinal(block);
        } catch (Exception e) {
            return null;
        }
    }

    // Verify both keys against the 20 km/h frame vector (spec 3.2) plus a round-trip.
    private static boolean selfTest() {
        byte[] plain = hexToBytes("D707A90000C878");   // D7 07 A9 00 00 C8 78
        byte[] a = encrypt(plain, KEY_A);
        byte[] b = encrypt(plain, KEY_B);
        if (a == null || b == null) return false;
        boolean okA = hex(a).equals("69570AC61E3B0F019ABFC5D6BFAC0A7E");
        boolean okB = hex(b).equals("CDEFA33F9725C32457ECF480C535A28A");
        byte[] rt = decrypt(a, KEY_A);
        boolean okRt = rt != null && rt.length >= 7 && hex(java.util.Arrays.copyOf(rt, 7)).equals("D707A90000C878");
        return okA && okB && okRt;
    }

    static byte[] hexToBytes(String h) {
        if (h == null) return new byte[0];
        String s = h.replaceAll("[^0-9a-fA-F]", "");
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i + 1 < s.length(); i += 2) {
            out[i / 2] = (byte) Integer.parseInt(s.substring(i, i + 2), 16);
        }
        return out;
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02X", x & 0xFF));
        return sb.toString();
    }
}
