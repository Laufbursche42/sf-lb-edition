// Laufbursche SoFlow Edition - a companion app for SoFlow e-scooters.
// Copyright (c) 2026 Laufbursche (https://github.com/Laufbursche42)
// Source-available under the PolyForm Noncommercial License 1.0.0 with Additional Terms. See license.md.

package com.lb.edition;

import com.lb.edition.Models.Family;
import com.lb.edition.Models.Proto;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the outgoing SoFlow command frames (phone -> scooter) in plaintext. Encryption, if the
 * active model requires it, is applied by the transport layer at send time, not here.
 *
 * D7/SO3 frame: [0xD7][LEN][OPCODE][BYTE3][PAYLOAD...][CHECKSUM], LEN = payload.length + 5,
 * CHECKSUM = additive 8-bit sum from LEN to the last payload byte (0xD7 not counted). BYTE3 is 0x00
 * for D7 and the rolling secret for SO3. SO6 frame: [GROUP][SUB][LEN][PAYLOAD...], whole frame AES.
 *
 * Each builder returns a {@link Frame} carrying the wire bytes plus the ack key the reply must echo
 * (spec 7.2): "op:OPCODE" for D7/SO3, "so6:GROUP:SUB" for SO6.
 */
final class CommandBuilder {

    private CommandBuilder() {}

    /** A built frame plus its expected ack key (may be null). */
    static final class Frame {
        final byte[] bytes;
        final String ackKey;
        Frame(byte[] bytes, String ackKey) { this.bytes = bytes; this.ackKey = ackKey; }
    }

    // ── low-level frame builders (spec 4) ──

    /** D7/SO3 frame. byte3 = 0x00 for D7, the SO3 secret for SO3. */
    static byte[] buildFrameD7(int opcode, int[] payload, int byte3) {
        if (payload == null) payload = new int[0];
        int len = (payload.length + 5) & 0xFF;
        byte[] frame = new byte[payload.length + 5];   // D7 + LEN + OP + BYTE3 + payload + SUM
        frame[0] = (byte) 0xD7;
        frame[1] = (byte) len;
        frame[2] = (byte) (opcode & 0xFF);
        frame[3] = (byte) (byte3 & 0xFF);
        for (int i = 0; i < payload.length; i++) frame[4 + i] = (byte) (payload[i] & 0xFF);
        int sum = 0;
        for (int i = 1; i < frame.length - 1; i++) sum = (sum + (frame[i] & 0xFF)) & 0xFF;
        frame[frame.length - 1] = (byte) sum;
        return frame;
    }

    /** SO6 frame: [group, sub, payloadLen, payload...]. No start byte, no checksum (AES applied later). */
    static byte[] buildFrameSO6(int group, int sub, int[] payload) {
        if (payload == null) payload = new int[0];
        byte[] frame = new byte[payload.length + 3];
        frame[0] = (byte) (group & 0xFF);
        frame[1] = (byte) (sub & 0xFF);
        frame[2] = (byte) (payload.length & 0xFF);
        for (int i = 0; i < payload.length; i++) frame[3 + i] = (byte) (payload[i] & 0xFF);
        return frame;
    }

    /** BE16 speed payload in 0.1-km/h steps (spec 4.4). */
    static int[] speedPayload(double kmh) {
        int v = (int) Math.round(kmh * 10.0);
        return new int[]{(v >> 8) & 0xFF, v & 0xFF};
    }

    /** SO3 rolling secret from three bytes of a received 0x1D frame (spec 4.2). 7-bit result. */
    static int so3CalcSecret(int b3, int b15, int b16) {
        int t = (b15 ^ b3) ^ (b16 ^ b3);
        t = (((t + 0xCE) & 0xFF) ^ 0xB2) & 0xFF;
        t = (((t + 0xA5) & 0xFF) ^ 0xCA) & 0xFF;
        t = (((t + (b3 & 0x0F)) & 0xFF) ^ 0x2B) & 0xFF;
        t = (((t + 0x33) & 0xFF) ^ 0x1D) & 0xFF;
        return t & 0x7F;
    }

    // ── helpers ──

    private static int byte3(Proto p, SettingsState s) {
        return p.family == Family.SO3 ? (s.so3Secret & 0xFF) : 0x00;
    }

    /** Old-SO4 packed byte 0: (currentMode<<1)|lowBit (spec 5.9). */
    private static int so4ModeByte0(SettingsState s, int lowBit) {
        return (((s.currentMode & 0xFF) << 1) | (lowBit & 1)) & 0xFF;
    }

    private static Frame d7(int op, int[] payload, int byte3) {
        return new Frame(buildFrameD7(op, payload, byte3), "op:" + (op & 0xFF));
    }

    private static Frame so6(int group, int sub, int[] payload) {
        return new Frame(buildFrameSO6(group, sub, payload), "so6:" + (group & 0xFF) + ":" + (sub & 0xFF));
    }

    private static boolean oldSo4(Proto p, SettingsState s) {
        return "so4".equals(p.variant) && !"v52".equals(Models.so4Ver(p, s));
    }

    // ── commands (spec 5) ──

    /** Max speed 0xA9, BE16 km/h*10. Only for speed-capable models; null otherwise. */
    static Frame setMaxSpeed(Proto p, SettingsState s, double kmh) {
        if (!Models.speedSupported(p, s)) return null;
        return d7(0xA9, speedPayload(kmh), byte3(p, s));
    }

    /** Ride mode eco0/normal1/sport2. Updates the maintained currentMode. */
    static Frame setSpeedMode(Proto p, SettingsState s, int mode) {
        if (!p.speed) return null;
        s.currentMode = mode & 0xFF;
        if (p.family == Family.SO3) return d7(0xA4, new int[]{0x00, mode & 0xFF}, byte3(p, s));
        if (oldSo4(p, s)) return d7(0xA0, new int[]{so4ModeByte0(s, 1), 0x00}, 0x00);
        return d7(0xA3, new int[]{mode & 0xFF}, 0x00);
    }

    /** Unlock (immobiliser off). Every family. */
    static Frame unlock(Proto p, SettingsState s) {
        if (p.family == Family.SO6) {
            int[] pin = p.so6pin ? new int[]{0x30, 0x30, 0x30, 0x30, 0x30, 0x30} : new int[0];
            return so6(0x05, 0x01, pin);
        }
        if (p.family == Family.SO3) return d7(0xA2, new int[]{0x00, 0x00}, byte3(p, s));
        if (oldSo4(p, s)) return d7(0xA0, new int[]{so4ModeByte0(s, 1), 0x00}, 0x00);
        return d7(0xA0, new int[]{0x00}, 0x00);
    }

    /** Lock (immobiliser on). Every family. */
    static Frame lock(Proto p, SettingsState s) {
        if (p.family == Family.SO6) return so6(0x05, 0x0C, new int[]{0x01});
        if (p.family == Family.SO3) return d7(0xA2, new int[]{0x00, 0x02}, byte3(p, s));
        if (oldSo4(p, s)) return d7(0xA0, new int[]{so4ModeByte0(s, 1), 0x01}, 0x00);
        return d7(0xA0, new int[]{0x01}, 0x00);
    }

    /** Battery unlock 0xD5 (D7 only). so4 only from V52; null if unsupported. */
    static Frame batteryUnlock(Proto p, SettingsState s) {
        if (p.family != Family.D7) return null;
        if ("so4".equals(p.variant)) {
            if (!"v52".equals(Models.so4Ver(p, s))) return null;
            return d7(0xD5, new int[]{0x01}, 0x00);
        }
        return d7(0xD5, new int[]{0x00}, 0x00);   // so5base
    }

    /** Front light on/off 0xA2 (so5base). */
    static Frame frontLight(boolean on) {
        return d7(0xA2, new int[]{on ? 0x01 : 0x00}, 0x00);
    }

    /** Dark mode on/off 0xD6 (so5base). Wire is inverted: on = 0x00. */
    static Frame darkMode(boolean on) {
        return d7(0xD6, new int[]{on ? 0x00 : 0x01}, 0x00);
    }

    /** Zero-start on/off 0xA5 (so5base). */
    static Frame zeroStart(boolean on) {
        return d7(0xA5, new int[]{on ? 0x01 : 0x00}, 0x00);
    }

    /** Indicator light (so4 path). v42 packs into 0xA0; v51 unsupported (null); else 0xA6. */
    static Frame indicator(Proto p, SettingsState s, boolean on) {
        if ("so4".equals(p.variant) && "v42".equals(Models.so4Ver(p, s)))
            return d7(0xA0, new int[]{so4ModeByte0(s, on ? 1 : 0), 0x00}, 0x00);
        if ("so4".equals(p.variant) && "v51".equals(Models.so4Ver(p, s))) return null;
        return d7(0xA6, new int[]{on ? 0x01 : 0x00}, 0x00);
    }

    /** Unit km/h vs mph. SO3 0xAB [00, imperial?02:00]; else 0xA7 [imperial]. Not offered on SO4. */
    static Frame setUnit(Proto p, SettingsState s, boolean imperial) {
        if (p.family == Family.SO3) return d7(0xAB, new int[]{0x00, imperial ? 0x02 : 0x00}, byte3(p, s));
        return d7(0xA7, new int[]{imperial ? 0x01 : 0x00}, 0x00);
    }

    /** Live-data nudge: D7 0x1D []; SO3 0xA0 [00,02]; SO6 {05,46} [01]. */
    static Frame liveNudge(Proto p, SettingsState s) {
        if (p.family == Family.SO6) return so6(0x05, 0x46, new int[]{0x01});
        if (p.family == Family.SO3) return d7(0xA0, new int[]{0x00, 0x02}, byte3(p, s));
        return d7(0x1D, new int[0], 0x00);
    }

    // ── connect handshake (spec 5.8) ──

    /** Immediate frames to send right after connect, per family. */
    static Frame[] afterConnect(Proto p, SettingsState s) {
        List<Frame> out = new ArrayList<>();
        if (p.family == Family.D7 && "so4".equals(p.variant)) {
            out.add(liveNudge(p, s));                                  // wait for version frame, then so4InitAfterVersion
        } else if (p.family == Family.D7) {
            out.add(d7(0xA6, new int[]{0x01}, 0x00));                  // setBleIndicatorLight(true)
            out.add(liveNudge(p, s));
        } else if (p.family == Family.SO3) {
            out.add(liveNudge(p, s));                                  // 0xA0 [00,02] appStatus poll
        } else if (p.family == Family.SO6) {
            out.add(so6(0x06, 0x01, new int[]{0x01}));                 // updateToken
            out.add(so6(0x05, 0x46, new int[]{0x01}));                 // startMonitoringRealtime
        }
        return out.toArray(new Frame[0]);
    }

    /** SO4 only: init frame to send once the firmware version is known. Null on V51. */
    static Frame so4InitAfterVersion(Proto p, SettingsState s) {
        if (!(p.family == Family.D7 && "so4".equals(p.variant))) return null;
        String ver = Models.so4Ver(p, s);
        if ("v52".equals(ver)) return d7(0xA6, new int[]{0x01}, 0x00);
        if ("v42".equals(ver)) return d7(0xA0, new int[]{so4ModeByte0(s, 1), 0x00}, 0x00);
        return null;   // v51 has no builder
    }
}
