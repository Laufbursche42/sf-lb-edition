// Laufbursche Edition - an app for SoFlow e-scooters.
// Copyright (c) 2026 Laufbursche (https://github.com/Laufbursche42)
// Source-available under the PolyForm Noncommercial License 1.0.0 with Additional Terms. See license.md.

package com.lb.edition;

/** Sanitizes strings before they go into a log line so caller-supplied text (BLE names, JS-bridge
 *  arguments) cannot forge or split log entries. */
public final class LogSan {

    private LogSan() {}

    /** Replace CR, LF, tab and other control characters with '_'. Null becomes "null". */
    public static String s(String v) {
        if (v == null) return "null";
        return v.replaceAll("[\\p{Cntrl}]", "_");
    }
}
