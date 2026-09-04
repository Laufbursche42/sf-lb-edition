// Laufbursche SoFlow Edition - a companion app for SoFlow e-scooters.
// Copyright (c) 2026 Laufbursche (https://github.com/Laufbursche42)
// Source-available under the PolyForm Noncommercial License 1.0.0 with Additional Terms. See license.md.

package com.lb.edition;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SoFlow model register and classification (spec 1). Maps advertised BLE names to a protocol family,
 * transport and crypto policy, and holds the model policy helpers (so4 version, speed/battery support,
 * encryption active). Everything here is a 1:1 port of the app's VehicleType routing (spec 1.2-1.5).
 */
final class Models {

    private Models() {}

    enum Family { D7, SO3, SO6 }

    /** GATT transport. SO6 swaps write and notify vs Nordic/KingMeter (spec 2). */
    enum Transport {
        NORDIC   ("6e400001-b5a3-f393-e0a9-e50e24dcca9e", "6e400002-b5a3-f393-e0a9-e50e24dcca9e", "6e400003-b5a3-f393-e0a9-e50e24dcca9e"),
        KINGMETER("43480001-f001-4b49-4e47-204d45544552", "43480002-f001-4b49-4e47-204d45544552", "43480003-f001-4b49-4e47-204d45544552"),
        SO6      ("60000001-0000-1000-8000-00805f9b34fb", "60000003-0000-1000-8000-00805f9b34fb", "60000002-0000-1000-8000-00805f9b34fb");
        final String service, write, notify;
        Transport(String s, String w, String n) { service = s; write = w; notify = n; }
    }

    /** Crypto policy (spec 3.3). mode: never/always/fw52. keySel: 0 none, 1 key A, 2 key B. */
    static final class CryptoPolicy {
        final String mode; final int keySel; final boolean decryptIncoming;
        CryptoPolicy(String mode, int keySel, boolean decryptIncoming) {
            this.mode = mode; this.keySel = keySel; this.decryptIncoming = decryptIncoming;
        }
        byte[] key() { return keySel == 1 ? Crypto.KEY_A : keySel == 2 ? Crypto.KEY_B : null; }
    }

    static final CryptoPolicy FW52     = new CryptoPolicy("fw52",   1, false);   // SO4, SO myTIER
    static final CryptoPolicy ALWAYS30 = new CryptoPolicy("always", 1, false);   // SO X, SO2*, SO5 Pro, SO One*
    static final CryptoPolicy ALWAYS20 = new CryptoPolicy("always", 2, true);    // SO6, SO4 UL
    static final CryptoPolicy NONE     = new CryptoPolicy("never",  0, false);   // SO1, SO2 Air, SO3, SO5

    /** One protocol definition. variant/so4ver may be null. */
    static final class Proto {
        final String id, name, variant, so4ver;
        final Family family;
        final String[] prefixes;
        final Transport transport;
        final CryptoPolicy crypto;
        final boolean speed, so6pin;
        Proto(String id, String name, Family family, String variant, String so4ver, String[] prefixes,
              Transport transport, CryptoPolicy crypto, boolean speed, boolean so6pin) {
            this.id = id; this.name = name; this.family = family; this.variant = variant; this.so4ver = so4ver;
            this.prefixes = prefixes; this.transport = transport; this.crypto = crypto; this.speed = speed; this.so6pin = so6pin;
        }
    }

    private static Proto p(String id, String name, Family fam, String variant, String so4ver, String[] pre,
                           Transport t, CryptoPolicy c, boolean speed, boolean so6pin) {
        return new Proto(id, name, fam, variant, so4ver, pre, t, c, speed, so6pin);
    }
    private static String[] a(String... s) { return s; }

    // ── Protocol register (spec 1.2) ──
    static final Map<String, Proto> PROTOCOLS = new LinkedHashMap<>();
    static {
        put(p("so4",           "SO4",             Family.D7,  "so4",     null,  a("SFSO4", "SFS4"),                                                     Transport.NORDIC,    FW52,     true,  false));
        put(p("somytier",      "SO myTIER",       Family.D7,  "so4",     null,  a("SFSOMT"),                                                            Transport.NORDIC,    FW52,     true,  false));
        put(p("sox",           "SO X",            Family.D7,  "so4",     "v52", a("SFSOX"),                                                             Transport.NORDIC,    ALWAYS30, true,  false));
        put(p("so4ul",         "SO4 UL",          Family.SO6, null,      null,  a("SFSO4UL"),                                                           Transport.NORDIC,    ALWAYS20, false, false));
        put(p("so1",           "SO1",             Family.SO3, null,      null,  a("SFSO1", "SFSC1", "SFS1"),                                            Transport.NORDIC,    NONE,     true,  false));
        put(p("so2air",        "SO2 Air",         Family.SO3, null,      null,  a("SFSO2", "SFSC2", "SFS2A", "SFS22"),                                  Transport.NORDIC,    NONE,     true,  false));
        put(p("so2air2",       "SO2 Air 2nd gen", Family.D7,  "so5base", null,  a("SFS2K", "SFS2Z"),                                                    Transport.KINGMETER, ALWAYS30, true,  false));
        put(p("so2zero",       "SO2 Zero",        Family.D7,  "so5base", null,  a("SFS2M"),                                                             Transport.KINGMETER, ALWAYS30, true,  false));
        put(p("so2grover",     "SO2 Grover",      Family.D7,  "so5base", null,  a("SFS2K7"),                                                            Transport.NORDIC,    ALWAYS30, true,  false));
        put(p("so2plusgrover", "SO2+ Grover",     Family.D7,  "so5base", null,  a("SFS2K1"),                                                            Transport.NORDIC,    ALWAYS30, true,  false));
        put(p("so3",           "SO3",             Family.SO3, null,      null,  a("SFSO3", "SFSC3", "SFS3", "QINGZ"),                                   Transport.NORDIC,    NONE,     true,  false));
        put(p("so5",           "SO5",             Family.SO3, null,      null,  a("SFSO5", "SFSC5"),                                                    Transport.NORDIC,    NONE,     true,  false));
        put(p("so5pro",        "SO5 Pro",         Family.D7,  "so5base", null,  a("SFS5"),                                                              Transport.NORDIC,    ALWAYS30, true,  false));
        put(p("so6",           "SO6",             Family.SO6, null,      null,  a("SFSO6"),                                                             Transport.SO6,       ALWAYS20, false, true));
        put(p("soone",         "SO One",          Family.D7,  "so5base", null,  a("SFSOB"),                                                             Transport.NORDIC,    ALWAYS30, true,  false));
        put(p("sooneplus",     "SO One+",         Family.D7,  "so5base", null,  a("SFSOJ", "SFS4J", "SFSOL", "SFSLP", "SFSMX", "SFSPE", "SFSPM"),       Transport.NORDIC,    ALWAYS30, true,  false));
        put(p("soonepro",      "SO One Pro",      Family.D7,  "so5base", null,  a("SFSOP", "SFSGT", "SFSRE"),                                           Transport.KINGMETER, ALWAYS30, true,  false));
    }
    private static void put(Proto pr) { PROTOCOLS.put(pr.id, pr); }

    /** Branded display alias (spec 1.3): inherits a parent proto but scans its own prefix. */
    static final class Branded { final String label, proto; final String[] prefixes;
        Branded(String label, String proto, String[] prefixes) { this.label = label; this.proto = proto; this.prefixes = prefixes; } }
    static final Map<String, Branded> BRANDED = new LinkedHashMap<>();
    static {
        BRANDED.put("so4progt",      new Branded("SO4 Pro GT / GT2",  "soonepro",  a("SFSGT")));
        BRANDED.put("so4procore2",   new Branded("SO4 Pro Core2",     "soonepro",  a("SFSRE")));
        BRANDED.put("so4promax",     new Branded("SO4 Pro Max",       "sooneplus", a("SFSMX")));
        BRANDED.put("so4promax2",    new Branded("SO4 Pro Max 2",     "sooneplus", a("SFSMX")));
        BRANDED.put("soonelite",     new Branded("SO One Lite",       "sooneplus", a("SFSOL")));
        BRANDED.put("soonelitepro",  new Branded("SO One Lite Pro",   "sooneplus", a("SFSLP")));
        BRANDED.put("sooneprime",    new Branded("SO One Prime",      "sooneplus", a("SFSPE")));
        BRANDED.put("sooneprimemax", new Branded("SO One Prime Max",  "sooneplus", a("SFSPM")));
    }

    /** Base protocol by id (spec 1.2). */
    static Proto get(String id) { return id == null ? null : PROTOCOLS.get(id); }

    /**
     * Resolve a dropdown key to a live Proto. A branded key inherits its parent proto but overrides
     * the display name, id and scan prefixes.
     */
    static Proto resolve(String key) {
        if (key == null) return null;
        Branded br = BRANDED.get(key);
        if (br != null) {
            Proto base = PROTOCOLS.get(br.proto);
            if (base == null) return null;
            return new Proto(key, br.label, base.family, base.variant, base.so4ver, br.prefixes,
                    base.transport, base.crypto, base.speed, base.so6pin);
        }
        return PROTOCOLS.get(key);
    }

    /**
     * Classify an advertised device name to a base protocol id, in the app's exact check order
     * (spec 1.4). Returns null for a non-SoFlow name.
     */
    static String classifyByName(String name) {
        if (name == null || name.isEmpty()) return null;
        String n = name;
        if (n.startsWith("SFSO1") || n.startsWith("SFSC1") || n.startsWith("SFS1")) return "so1";
        if (n.startsWith("SFS2K7")) {                       // serial weiche: substring(7) >= 3000000 -> Grover
            long serial = n.length() >= 7 ? parseLong(n.substring(7)) : -1;
            return serial >= 3000000 ? "so2grover" : "so2air2";
        }
        if (n.startsWith("SFS2K1")) return "so2plusgrover";
        if (n.startsWith("SFS2K") || n.startsWith("SFS2Z")) return "so2air2";
        if (n.startsWith("SFS2Z") || n.startsWith("SFS2M")) return "so2zero";   // SFS2Z dead here, SFS2M wins
        if (n.startsWith("SFSO2") || n.startsWith("SFSC2") || n.startsWith("SFS2A") || n.startsWith("SFS22")) return "so2air";
        if (n.startsWith("SFSO3") || n.startsWith("SFSC3") || n.startsWith("SFS3") || n.startsWith("QINGZ")) return "so3";
        if (n.startsWith("SFSOB")) return "soone";
        if (n.startsWith("SFSOJ") || n.startsWith("SFS4J") || n.startsWith("SFSOL") || n.startsWith("SFSLP")
                || n.startsWith("SFSMX") || n.startsWith("SFSPE") || n.startsWith("SFSPM")) return "sooneplus";
        if (n.startsWith("SFSOP") || n.startsWith("SFSGT") || n.startsWith("SFSRE")) return "soonepro";
        if (n.startsWith("SFSO4UL")) return "so4ul";
        if (n.startsWith("SFSO4") || n.startsWith("SFS4")) return "so4";
        if (n.startsWith("SFSO5") || n.startsWith("SFSC5")) return "so5";
        if (n.startsWith("SFS5")) return "so5pro";
        if (n.startsWith("SFSOMT")) return "somytier";
        if (n.startsWith("SFSOX")) return "sox";
        if (n.startsWith("SFSO6")) return "so6";
        return null;
    }

    /** Fallback when the plain name "SoFlow" gives no model: classify by the GATT service (spec 1.5). */
    static String protoFromTransport(Transport t) {
        if (t == Transport.SO6) return "so6";
        if (t == Transport.KINGMETER) return "soonepro";
        return "soone";
    }

    // ── model policy helpers (spec 3.4, 5.9) ──

    /** SO4 firmware >= 5.2 -> protocol V52 (spec 3.4). Unknown firmware: not yet V52. */
    static boolean protocolIsV52(SettingsState s) {
        return s.fwMajor != null && (s.fwMajor > 5 || (s.fwMajor == 5 && s.fwMinor != null && s.fwMinor >= 2));
    }

    /** so4ver override, else derived from firmware (spec 5.9). Unknown firmware assumes newest (v52). */
    static String so4Ver(Proto pr, SettingsState s) {
        if (pr.so4ver != null) return pr.so4ver;
        if (s.fwMajor == null) return "v52";
        if (s.fwMajor <= 4) return "v42";
        if (s.fwMajor == 5 && s.fwMinor != null && s.fwMinor <= 1) return "v51";
        return "v52";
    }

    /** A model can set max speed unless it is a no-speed family or an SO4 on old V42 firmware. */
    static boolean speedSupported(Proto pr, SettingsState s) {
        if (!pr.speed) return false;
        if ("so4".equals(pr.variant) && "v42".equals(so4Ver(pr, s))) return false;
        return true;
    }

    /** Battery unlock exists on so5base (always) and on the SO4 path from V52 (spec 5). */
    static boolean batterySupported(Proto pr, SettingsState s) {
        if (pr.family != Family.D7) return false;
        if (!"so4".equals(pr.variant)) return true;
        if ("v52".equals(pr.so4ver)) return true;
        return s.fwMajor != null && "v52".equals(so4Ver(pr, s));
    }

    /** Whether outgoing frames are encrypted right now (spec 3.4). */
    static boolean encActive(Proto pr, SettingsState s) {
        String mode = pr.crypto.mode;
        if ("never".equals(mode)) return false;
        if ("always".equals(mode)) return true;
        return protocolIsV52(s);   // fw52: only from firmware 5.2
    }

    /** The AES key for outgoing/incoming frames of this proto, or null if none. */
    static byte[] encKey(Proto pr) { return pr.crypto.key(); }

    private static long parseLong(String s) {
        try {
            StringBuilder digits = new StringBuilder();
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c >= '0' && c <= '9') digits.append(c); else break;
            }
            return digits.length() == 0 ? -1 : Long.parseLong(digits.toString());
        } catch (Exception e) {
            return -1;
        }
    }
}
