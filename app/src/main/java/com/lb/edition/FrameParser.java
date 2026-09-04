// Laufbursche Edition - an app for SoFlow e-scooters.
// Copyright (c) 2026 Laufbursche (https://github.com/Laufbursche42)
// Source-available under the PolyForm Noncommercial License 1.0.0 with Additional Terms. See license.md.

package com.lb.edition;

import com.lb.edition.Models.Family;
import com.lb.edition.Models.Proto;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Decodes incoming SoFlow BLE notifications (scooter -> phone). Accepts both 0xD7 and 0xD5 start
 * bytes (KingMeter units reply with 0xD5), verifies the additive checksum, dispatches by family and
 * decodes the telemetry per spec 6. SO6 frames are AES-decrypted first. onNotify returns the ack
 * key(s) the frame echoes so the transport layer can resolve a pending command (spec 7.2).
 *
 * Thread-safety: onNotify() runs on the GATT callback thread; toJson() may run on the UI thread.
 * The decoded model is guarded by this instance's monitor.
 */
final class FrameParser {

    private static final String[] EMPTY = new String[0];
    private static final String[] MODE_NAMES = {"Eco", "Normal", "Sport"};

    private final SettingsState settings;

    // Active protocol (set by the transport layer on connect); decoding depends on family/variant.
    volatile Proto proto;

    // Phone-side RSSI and advertised name (set by the transport layer; not part of the frames).
    volatile int rssi = 0;
    volatile String btName = "";

    // ── decoded live model (NaN / -1 / "" mean "not seen yet") ──
    private double speed = Double.NaN, voltage = Double.NaN, current = Double.NaN, power = Double.NaN;
    private double tripKm = Double.NaN, totalKm = Double.NaN;
    private int battery = -1, mode = -1, error = -1;
    private int locked = -1, headlight = -1, imperial = -1, darkMode = -1;   // tri-state: -1 unknown
    private String errorHex = "";
    private String fw = "", fwDisplay = "", fwCpu = "";

    FrameParser(SettingsState settings) {
        this.settings = settings;
    }

    /** Set the active protocol so decoding knows the family/variant. */
    void setProto(Proto p) { this.proto = p; }

    /** Clear the decoded model (fresh connect). */
    synchronized void reset() {
        speed = voltage = current = power = tripKm = totalKm = Double.NaN;
        battery = mode = error = -1;
        locked = headlight = imperial = darkMode = -1;
        errorHex = fw = fwDisplay = fwCpu = "";
    }

    /**
     * Decode one notification frame. Returns the candidate ack keys the frame echoes (usually one;
     * a SO6 {05,0E} lock status carries two), for the transport layer to resolve a pending command.
     */
    synchronized String[] onNotify(byte[] value) {
        if (value == null || value.length < 2) return EMPTY;
        Proto p = proto;
        Family fam = (p != null) ? p.family : null;
        try {
            if (fam == Family.SO6) return handleSO6(value, p);
            int[] b = toInts(value);
            if (b[0] != 0xD7 && b[0] != 0xD5) return EMPTY;   // ignore foreign frames
            if (b.length < 3) return EMPTY;
            int op = b[2] & 0xFF;
            if (fam == Family.SO3) {
                if (op == 0x1D) { updateSo3Secret(b); decodeSo3Realtime(b); }
                else if (op == 0x2D) decodeSo3Status2(b);
                return new String[]{"op:" + op};
            }
            // D7 family
            if (p != null && "so4".equals(p.variant)) {
                if (b.length > 12) {
                    int major = b[12] >> 4, minor = b[12] & 0x0F;
                    if (major > 0 && major < 15) applyVersion(major, minor);
                }
                if (b.length >= 20 && op == 0x1D) decodeRealtimeSo4(b);
            } else {
                if (op == 0x1D) decodeRealtimeSo5(b);
            }
            return new String[]{"op:" + op};
        } catch (Throwable ignored) {
            return EMPTY;   // never let a malformed frame break the pipeline
        }
    }

    // ── SO6 ──

    private String[] handleSO6(byte[] value, Proto p) {
        byte[] data = value;
        if (p.crypto.decryptIncoming) {
            if (!Crypto.OK) return EMPTY;
            byte[] key = p.crypto.key();
            byte[] dec = (key != null) ? Crypto.decrypt(value, key) : null;
            if (dec == null) return EMPTY;
            data = dec;
        }
        int[] d = toInts(data);
        if (d.length < 3) return EMPTY;
        int g = d[0] & 0xFF, sub = d[1] & 0xFF;
        if (g == 0x05 && sub == 0x46) decodeRealtimeSo6(d);
        if (g == 0x05 && sub == 0x0E) return new String[]{"so6:5:14", "so6:5:12", "so6:5:1"};
        return new String[]{"so6:" + g + ":" + sub};
    }

    // ── telemetry decoders (spec 6) ──

    /** SO4 realtime 0x1D (spec 6.1). len >= 20 ensured by caller. */
    private void decodeRealtimeSo4(int[] b) {
        int st = b[4];
        mode = (st >> 1) & 0x07;
        imperial = (st & 0x10) != 0 ? 1 : 0;
        locked = (st & 0x80) != 0 ? 1 : 0;
        headlight = (st & 0x01) != 0 ? 1 : 0;
        speed = u16(b, 5) / 10.0;
        voltage = u16(b, 7) / 10.0;
        current = u16(b, 9) / 10.0;
        error = b[11] & 0xFF;
        fwDisplay = (b[13] >> 4) + "." + (b[13] & 0x0F);
        fwCpu = (b[14] >> 4) + "." + (b[14] & 0x0F);
        tripKm = u16(b, 15) / 10.0;
        totalKm = u16(b, 17);
        battery = b[19] & 0xFF;
    }

    /** So5ProBase realtime 0x1D with length guards (spec 6.2). */
    private void decodeRealtimeSo5(int[] b) {
        if (b.length < 11) return;
        int st = b[4];
        mode = (st >> 1) & 0x07;
        imperial = (st & 0x10) != 0 ? 1 : 0;
        locked = (st & 0x80) != 0 ? 1 : 0;
        headlight = (st & 0x01) != 0 ? 1 : 0;
        speed = u16(b, 5) / 10.0;
        voltage = u16(b, 7) / 10.0;
        current = u16(b, 9) / 10.0;
        if (b.length >= 15) {
            StringBuilder eh = new StringBuilder();
            boolean ok = true;
            for (int i = 11; i < 15; i++) { if (b[i] != 0) ok = false; eh.append(String.format("%02X", b[i] & 0xFF)); }
            errorHex = eh.toString();
            error = ok ? 0 : 1;
        }
        if (b.length >= 18) {
            fw = (b[15] >> 4) + "." + (b[15] & 0x0F);
            fwDisplay = (b[16] >> 4) + "." + (b[16] & 0x0F);
            fwCpu = (b[17] >> 4) + "." + (b[17] & 0x0F);
        }
        if (b.length >= 22) { tripKm = u16(b, 18) / 10.0; totalKm = u16(b, 20); }
        if (b.length >= 23) battery = b[22] & 0xFF;
        if (b.length >= 27) darkMode = (b[26] == 0) ? 1 : 0;   // darkMode active when the byte is 0
    }

    /** SO3 realtime 0x1D (spec 6.3). Mode mapping uncertain. */
    private void decodeSo3Realtime(int[] b) {
        if (b.length < 11) return;
        int st = b[4];
        mode = (st >> 1) & 0x07;
        imperial = (st & 0x10) != 0 ? 1 : 0;
        speed = u16(b, 5) / 10.0;
        voltage = u16(b, 7) / 10.0;
        current = u16(b, 9) / 10.0;
        if (b.length >= 15) power = u16(b, 11) / 10.0;   // b[13..14] energy: not surfaced
    }

    /** SO3 status2 0x2D (spec 6.3): firmware plus trip/total. */
    private void decodeSo3Status2(int[] b) {
        if (b.length < 10) return;
        fw = (b[4] >> 4) + "." + (b[4] & 0x0F);
        tripKm = u16(b, 6) / 10.0;
        totalKm = u16(b, 8);
    }

    /** SO6 realtime {05,46} (spec 6.4): voltage/current/power only. */
    private void decodeRealtimeSo6(int[] d) {
        if (d.length < 5) return;
        voltage = u16(d, 3) / 10.0;
        if (d.length >= 7) current = u16(d, 5) / 10.0;
        if (d.length >= 9) power = u16(d, 7) / 10.0;
    }

    /** SO4 firmware from byte 12 (spec 6.1); drives the plaintext/AES choice via SettingsState. */
    private void applyVersion(int major, int minor) {
        settings.fwMajor = major;
        settings.fwMinor = minor;
        fw = major + "." + minor;
    }

    /** SO3 rolling secret from b3/b15/b16 of a 0x1D frame (spec 4.2). */
    private void updateSo3Secret(int[] b) {
        if (b.length < 17) return;
        int sec = CommandBuilder.so3CalcSecret(b[3], b[15], b[16]);
        if (sec != settings.so3Secret) settings.so3Secret = sec;
    }

    // ── helpers ──

    private static int[] toInts(byte[] v) {
        int[] b = new int[v.length];
        for (int i = 0; i < v.length; i++) b[i] = v[i] & 0xFF;
        return b;
    }

    private static int u16(int[] b, int i) { return ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF); }

    // ── JSON serialisation ──

    synchronized String toJson() {
        JSONObject o = new JSONObject();
        try {
            putD(o, "speed", speed);
            putD(o, "voltage", voltage);
            putD(o, "current", current);
            if (!Double.isNaN(power)) o.put("power", round2(power));
            putI(o, "battery", battery);
            if (mode >= 0) { o.put("mode", mode); o.put("modeText", mode < MODE_NAMES.length ? MODE_NAMES[mode] : ("Mode " + mode)); }
            putTri(o, "locked", locked);
            putTri(o, "headlight", headlight);
            putTri(o, "imperial", imperial);
            putTri(o, "darkMode", darkMode);
            putI(o, "error", error);
            if (!errorHex.isEmpty()) o.put("errorHex", errorHex);
            if (!fw.isEmpty()) o.put("fw", fw);
            if (settings.fwMajor != null) o.put("fwMajor", settings.fwMajor);
            if (settings.fwMinor != null) o.put("fwMinor", settings.fwMinor);
            if (!fwDisplay.isEmpty()) o.put("fwDisplay", fwDisplay);
            if (!fwCpu.isEmpty()) o.put("fwCpu", fwCpu);
            putD(o, "tripKm", tripKm);
            putD(o, "totalKm", totalKm);

            Proto p = proto;
            if (p != null) {
                o.put("model", p.name);
                o.put("family", p.family.name());
                o.put("variant", p.variant == null ? "" : p.variant);
            }
            o.put("btName", btName);
            o.put("rssi", rssi);
            o.put("ts", System.currentTimeMillis());
        } catch (JSONException ignored) {
        }
        return o.toString();
    }

    private static void putD(JSONObject o, String k, double v) throws JSONException {
        if (!Double.isNaN(v)) o.put(k, round1(v));
    }

    private static void putI(JSONObject o, String k, int v) throws JSONException {
        if (v >= 0) o.put(k, v);
    }

    private static void putTri(JSONObject o, String k, int v) throws JSONException {
        if (v >= 0) o.put(k, v != 0);
    }

    private static double round1(double v) { return Math.round(v * 10.0) / 10.0; }

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
}
