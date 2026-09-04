// Laufbursche SoFlow Edition - a companion app for SoFlow e-scooters.
// Copyright (c) 2026 Laufbursche (https://github.com/Laufbursche42)
// Source-available under the PolyForm Noncommercial License 1.0.0 with Additional Terms. See license.md.

package com.lb.edition;

/**
 * Minimal local state the SoFlow commands and decoders need. SoFlow has no big settings frame like
 * Teverun, so this only holds the few values the protocol carries between frames: the last ride mode
 * (for the old-SO4 packed mode byte), the SO3 rolling secret, the SO4 firmware version (drives the
 * plaintext/AES choice) and the local speed lock flag (SoFlow reports no speed-limit state).
 */
final class SettingsState {

    // Last ride mode written, packed into byte 0 of old-SO4 mode/lock frames. Starts at 1 (normal).
    volatile int currentMode = 1;

    // SO3 rolling secret placed in byte 3 of outgoing SO3 frames; recomputed from each 0x1D frame.
    volatile int so3Secret = 0;

    // SO4 firmware version from byte 12 of an inbound frame; null until a frame reveals it.
    volatile Integer fwMajor = null;
    volatile Integer fwMinor = null;

    // Local speed lock/unlock state. SoFlow reports no speed-limit state, so we track it ourselves.
    volatile boolean speedUnlocked = false;

    /** Reset on every fresh connect (spec 7.3). */
    synchronized void resetOnConnect() {
        currentMode = 1;
        so3Secret = 0;
        fwMajor = null;
        fwMinor = null;
        speedUnlocked = false;
    }
}
