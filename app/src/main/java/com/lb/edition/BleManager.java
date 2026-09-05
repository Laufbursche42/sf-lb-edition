// Laufbursche Edition - an app for SoFlow e-scooters.
// Copyright (c) 2026 Laufbursche (https://github.com/Laufbursche42)
// Source-available under the PolyForm Noncommercial License 1.0.0 with Additional Terms. See license.md.

package com.lb.edition;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.lb.edition.Models.Family;
import com.lb.edition.Models.Proto;
import com.lb.edition.Models.Transport;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Native BLE layer for SoFlow scooters. Connection flow: scan by name prefix (SFS/QINGZ/SoFlow),
 * classify by advertised name, connect GATT, resolve the transport service (Nordic UART, KingMeter
 * or SO6, in that fallback order), pick its write/notify characteristics, enable notifications, then
 * run the per-family post-connect handshake (spec 5.8). Outgoing frames are encrypted per the model
 * policy at send time; SO6 incoming frames are decrypted by the parser. There is no firmware OTA on
 * SoFlow, so no flashing path exists here.
 *
 * All public entry points are null/exception-safe so nothing ever throws across the JS bridge.
 */
@SuppressLint("MissingPermission")
final class BleManager {

    private static final String TAG = "lbble";

    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    // Generic Access service + Device Name: the reliable post-connect source of the BLE name.
    private static final UUID GAP_SERVICE     = UUID.fromString("00001800-0000-1000-8000-00805f9b34fb");
    private static final UUID GAP_DEVICE_NAME = UUID.fromString("00002a00-0000-1000-8000-00805f9b34fb");

    // Broad SoFlow scan filter (spec 1.5): every model prefix begins SFS, plus QINGZ (SO3) and the
    // plain "SoFlow"/"SOFLOW" clear name newer units advertise.
    private static final String[] NAME_PREFIXES = {"SFS", "QINGZ", "SoFlow", "SOFLOW"};
    // Transport resolve order when the model does not pin one, or its expected service is absent.
    private static final Transport[] TRANSPORT_ORDER = {Transport.NORDIC, Transport.KINGMETER, Transport.SO6};

    private static final long DISCOVER_DELAY_MS = 1500;   // the module needs ~1.5 s after connect
    private static final long WRITE_SETTLE_MS = 250;      // spec 7.1: space frames 250 ms apart
    private static final long ACK_TIMEOUT_MS = 3000;      // spec 7.2: ack window
    private static final long SO4_LINK_TIMEOUT_MS = 2500; // spec 5.8: SO4 version-wait fallback
    private static final long RECONNECT_BASE_MS = 3000;
    private static final long RECONNECT_MAX_MS = 30000;
    private static final int  MTU_TARGET = 247;           // fit a full telemetry frame in one notification
    private static final long PUSH_INTERVAL_MS = 500;     // live-data push ~2x/s

    interface Listener {
        void onScanResults(String jsonArray);
        void onState(String json);
        void onLiveData(String json);
    }

    private final Context appCtx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    private final SettingsState settings = new SettingsState();
    private final FrameParser parser = new FrameParser(settings);

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private volatile boolean scanning = false;
    private final Map<String, ScanEntry> found = new LinkedHashMap<>();

    private volatile BluetoothGatt gatt;
    private volatile BluetoothGattCharacteristic notifyChar;
    private volatile BluetoothGattCharacteristic writeChar;
    private volatile boolean notifyReady = false;
    private volatile boolean connected = false;
    private volatile boolean charsSetupDone = false;

    private String desiredAddress;
    private String deviceName = "";
    private volatile String forcedProtoId;   // manual model override; null = auto classify by name/service

    // Active model + transport, resolved on connect (name first, service as fallback for "SoFlow").
    private volatile Proto activeProto;
    private volatile Transport usedTransport;
    // SO4 sends the init frame once the firmware version (byte 12) is known; guards the one-shot.
    private volatile boolean initSent = false;

    private volatile long reconnectDelay = RECONNECT_BASE_MS;

    // write serialisation (spec 7.1)
    private final ArrayDeque<CommandBuilder.Frame> writeQueue = new ArrayDeque<>();
    private boolean writing = false;

    // ack tracking (spec 7.2): ackKey -> timeout runnable
    private final Map<String, Runnable> pendingAcks = new HashMap<>();

    BleManager(Context ctx, Listener listener) {
        this.appCtx = ctx.getApplicationContext();
        this.listener = listener;
        try {
            BluetoothManager bm = (BluetoothManager) appCtx.getSystemService(Context.BLUETOOTH_SERVICE);
            if (bm != null) adapter = bm.getAdapter();
        } catch (Throwable t) {
            Log.e(TAG, "adapter init failed", t);
        }
    }

    private static final class ScanEntry {
        String name;
        String address;
        int rssi;
    }

    // ── Scan ──

    void scan() {
        try {
            if (adapter == null || !adapter.isEnabled()) {
                Log.w(TAG, "scan: adapter unavailable/disabled");
                return;
            }
            scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) return;
            synchronized (found) { found.clear(); }
            if (scanning) return;
            ScanSettings s = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();
            scanner.startScan(null, s, scanCallback);
            scanning = true;
            Log.i(TAG, "scan started");
        } catch (Throwable t) {
            Log.e(TAG, "scan failed", t);
        }
    }

    void stopScan() {
        try {
            if (scanner != null && scanning) scanner.stopScan(scanCallback);
        } catch (Throwable t) {
            Log.e(TAG, "stopScan failed", t);
        } finally {
            scanning = false;
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            handleScan(result);
        }

        @Override
        public void onBatchScanResults(java.util.List<ScanResult> results) {
            if (results != null) for (ScanResult r : results) handleScan(r);
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.w(TAG, "scan failed code=" + errorCode);
            scanning = false;
        }
    };

    private void handleScan(ScanResult result) {
        try {
            if (result == null || result.getDevice() == null) return;
            String addr = result.getDevice().getAddress();
            String name = null;
            if (result.getScanRecord() != null) name = result.getScanRecord().getDeviceName();
            if (name == null || name.isEmpty()) {
                try { name = result.getDevice().getName(); } catch (Throwable ignored) {}
            }
            if (!nameAccepted(name)) return;
            ScanEntry e = new ScanEntry();
            e.name = (name == null) ? "" : name;
            e.address = addr;
            e.rssi = result.getRssi();
            boolean changed;
            synchronized (found) {
                ScanEntry prev = found.get(addr);
                changed = prev == null;
                found.put(addr, e);
            }
            if (changed) { Log.i(TAG, "found: " + e.name + " [" + addr + "] rssi=" + e.rssi); pushScanResults(); }
        } catch (Throwable t) {
            Log.e(TAG, "handleScan failed", t);
        }
    }

    private static boolean nameAccepted(String name) {
        if (name == null) return false;
        for (String p : NAME_PREFIXES) if (name.startsWith(p)) return true;
        return false;
    }

    private void pushScanResults() {
        try {
            JSONArray arr = new JSONArray();
            synchronized (found) {
                for (ScanEntry e : found.values()) {
                    JSONObject o = new JSONObject();
                    o.put("name", e.name);
                    o.put("address", e.address);
                    o.put("rssi", e.rssi);
                    arr.put(o);
                }
            }
            if (listener != null) listener.onScanResults(arr.toString());
        } catch (Throwable t) {
            Log.e(TAG, "pushScanResults failed", t);
        }
    }

    // ── Connect / disconnect ──

    /** Connect and seed the already-known BLE name so classification/display work immediately. */
    void connect(String address, String name) {
        if (name != null && !name.trim().isEmpty()) {
            deviceName = name.trim();
            parser.btName = deviceName;
        }
        connect(address);
    }

    void connect(String address) {
        try {
            if (address == null || address.trim().isEmpty() || adapter == null) return;
            desiredAddress = address.trim();
            stopScan();
            synchronized (found) {
                ScanEntry e = found.get(desiredAddress);
                if (e != null && e.name != null) deviceName = e.name;
            }
            closeGatt();
            BluetoothDevice dev = adapter.getRemoteDevice(desiredAddress);
            if (deviceName == null || deviceName.isEmpty()) {
                try { String n = dev.getName(); if (n != null) deviceName = n; } catch (Throwable ignored) {}
            }
            parser.btName = deviceName == null ? "" : deviceName;
            // Classify by advertised name now (spec 1.4). "SoFlow"/"SOFLOW" clear names give null and
            // are resolved from the GATT service after discovery (spec 1.5).
            classifyByName();
            Log.i(TAG, "connect() -> " + desiredAddress + " name=" + deviceName
                    + " proto=" + (activeProto == null ? "(by service)" : activeProto.id));
            pushState("connecting");
            gatt = dev.connectGatt(appCtx, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        } catch (Throwable t) {
            Log.e(TAG, "connect failed", t);
        }
    }

    private void classifyByName() {
        if (forcedProtoId != null) { activeProto = Models.resolve(forcedProtoId); return; }   // manual override
        String id = Models.classifyByName(deviceName);
        activeProto = (id != null) ? Models.get(id) : null;   // null -> resolve from service later
    }

    /** Manual model override for a wrong auto-detection. null/"auto" restores name-based classification. */
    void setForcedModel(String id) {
        forcedProtoId = (id == null || id.isEmpty() || "auto".equals(id)) ? null : id;
        String addr = desiredAddress;
        if (addr != null && adapter != null) {   // reconnect so the chosen transport and crypto apply
            try { closeGatt(); } catch (Throwable ignored) {}
            connected = false;
            notifyReady = false;
            connect(addr);
        }
    }

    void disconnect() {
        desiredAddress = null;   // user-initiated: no auto-reconnect
        stopPush();
        try {
            if (gatt != null) gatt.disconnect();
        } catch (Throwable t) {
            Log.e(TAG, "disconnect failed", t);
        }
        closeGatt();
        connected = false;
        notifyReady = false;
        pushState("disconnected");
    }

    /** @return JSON {"address","name"} of the last successfully connected scooter or "" if none. */
    String lastDeviceJson() {
        try {
            SharedPreferences sp = appCtx.getSharedPreferences("lb", Context.MODE_PRIVATE);
            String addr = sp.getString("last_device_addr", "");
            if (addr == null || addr.isEmpty()) return "";
            String name = sp.getString("last_device_name", "");
            JSONObject o = new JSONObject();
            o.put("address", addr);
            o.put("name", name == null ? "" : name);
            return o.toString();
        } catch (Throwable t) {
            Log.e(TAG, "lastDeviceJson failed", t);
            return "";
        }
    }

    /** Reconnect to the remembered scooter. No-ops if already connected/busy or nothing is stored. */
    void connectLast() {
        try {
            if (connected || desiredAddress != null) return;
            SharedPreferences sp = appCtx.getSharedPreferences("lb", Context.MODE_PRIVATE);
            String addr = sp.getString("last_device_addr", "");
            String name = sp.getString("last_device_name", "");
            if (addr != null && !addr.isEmpty()) {
                Log.i(TAG, "connectLast() -> " + addr);
                connect(addr, name);
            }
        } catch (Throwable t) {
            Log.e(TAG, "connectLast failed", t);
        }
    }

    private void closeGatt() {
        try {
            if (gatt != null) gatt.close();
        } catch (Throwable ignored) {
        } finally {
            gatt = null;
            notifyChar = null;
            writeChar = null;
            notifyReady = false;
            charsSetupDone = false;
            synchronized (writeQueue) { writeQueue.clear(); writing = false; }
            clearAcks();
        }
    }

    // ── GATT callback ──

    private long frameCount = 0;

    // Reassembly buffer for D7/SO3 frames split across notifications when the MTU is small (spec 6:
    // the SoOne realtime frame is 27 bytes, larger than the default 20-byte notification payload).
    private byte[] rxBuf = null;

    /**
     * Stitch a D7/SO3 notification back into a whole frame. A chunk starting with 0xD7/0xD5 begins a
     * new frame; anything else is a continuation and is appended. SO6 frames are AES full blocks and
     * pass through untouched. The decoders re-read the growing buffer each chunk (length-guarded), so
     * fields at the tail (battery, darkMode) appear once the last chunk arrives.
     */
    private byte[] reassemble(byte[] v) {
        Models.Proto p = parser.proto;
        if (p != null && p.family == Models.Family.SO6) return v;   // AES full frames: no reassembly
        if (v.length == 0) return v;
        int b0 = v[0] & 0xFF;
        if (b0 == 0xD7 || b0 == 0xD5) {
            rxBuf = v.clone();                                       // frame start
        } else if (rxBuf != null && rxBuf.length < 64) {
            byte[] merged = new byte[rxBuf.length + v.length];
            System.arraycopy(rxBuf, 0, merged, 0, rxBuf.length);
            System.arraycopy(v, 0, merged, rxBuf.length, v.length);
            rxBuf = merged;                                          // continuation
        } else {
            rxBuf = v.clone();
        }
        return rxBuf;
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                Log.i(TAG, "GATT connected");
                pushState("discovering");
                main.postDelayed(() -> {
                    // Raise the MTU first so a full (up to 27-byte) telemetry frame arrives in one
                    // notification; discovery then runs from onMtuChanged. Fall back to direct discovery
                    // if the request cannot be issued.
                    boolean asked = false;
                    try { asked = (gatt != null) && gatt.requestMtu(MTU_TARGET); } catch (Throwable ignored) {}
                    if (!asked) { try { if (gatt != null) gatt.discoverServices(); } catch (Throwable ignored) {} }
                }, DISCOVER_DELAY_MS);
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                Log.i(TAG, "GATT disconnected status=" + status);
                connected = false;
                notifyReady = false;
                rxBuf = null;
                stopPush();
                main.removeCallbacks(so4LinkTimeout);
                closeGatt();
                pushState("disconnected");
                if (desiredAddress != null) {
                    long delay = reconnectDelay;
                    Log.i(TAG, "scheduling reconnect in " + delay + " ms (backoff)");
                    reconnectDelay = Math.min(reconnectDelay * 2, RECONNECT_MAX_MS);
                    main.postDelayed(() -> {
                        if (desiredAddress != null) connect(desiredAddress);
                    }, delay);
                }
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            Log.i(TAG, "MTU changed to " + mtu + " status=" + status);
            try { if (g != null) g.discoverServices(); } catch (Throwable ignored) {}
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            try {
                Log.i(TAG, "onServicesDiscovered status=" + status + " count=" + (g == null ? 0 : g.getServices().size()));
                ensureDeviceName(g);
                if (!readGapDeviceName(g)) {
                    setupCharacteristics(g);
                }
            } catch (Throwable t) {
                Log.e(TAG, "onServicesDiscovered failed", t);
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int status) {
            Log.i(TAG, "CCCD write status=" + status);
            onNotifyReady(g);
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            synchronized (writeQueue) { writing = false; }
            main.postDelayed(BleManager.this::drainWriteQueue, WRITE_SETTLE_MS);
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            try {
                if (c != null && GAP_DEVICE_NAME.equals(c.getUuid())) {
                    String n = null;
                    try { n = c.getStringValue(0); } catch (Throwable ignored) {}
                    if (n != null) n = n.trim();
                    if (n != null && !n.isEmpty()) {
                        deviceName = n;
                        parser.btName = deviceName;
                        try {
                            appCtx.getSharedPreferences("lb", Context.MODE_PRIVATE).edit()
                                    .putString("last_device_name", deviceName).apply();
                        } catch (Throwable ignored) {}
                        Log.i(TAG, "GAP device name = " + deviceName);
                    }
                }
            } catch (Throwable t) {
                Log.e(TAG, "onCharacteristicRead failed", t);
            } finally {
                setupCharacteristics(g);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            try {
                byte[] v = c.getValue();
                if (v == null) return;
                byte[] frame = reassemble(v);               // stitch MTU-split D7/SO3 frames back together
                String[] acks = parser.onNotify(frame);     // decodes telemetry + SO6 decrypt
                if (acks != null) for (String key : acks) resolveAck(key);
                maybeSendSo4Init();                          // SO4: fire init once the version is known
                if (frameCount++ % 50 == 0) Log.i(TAG, "rx frames=" + frameCount + " last=" + v.length + "b");
            } catch (Throwable t) {
                Log.e(TAG, "onCharacteristicChanged failed", t);
            }
        }

        @Override
        public void onReadRemoteRssi(BluetoothGatt g, int rssi, int status) {
            parser.rssi = rssi;
            parser.btName = deviceName == null ? "" : deviceName;
        }
    };

    /** CCCD written (or forced): notifications live. Reset state and run the family handshake. */
    private void onNotifyReady(BluetoothGatt g) {
        notifyReady = true;
        connected = true;
        reconnectDelay = RECONNECT_BASE_MS;
        settings.resetOnConnect();
        parser.reset();
        initSent = false;
        clearAcks();
        ensureDeviceName(g);
        try {
            SharedPreferences.Editor ed = appCtx.getSharedPreferences("lb", Context.MODE_PRIVATE).edit()
                    .putString("last_device_addr", desiredAddress);
            if (deviceName != null && !deviceName.isEmpty()) ed.putString("last_device_name", deviceName);
            ed.apply();
        } catch (Throwable ignored) {}
        parser.setProto(activeProto);
        pushState("connected");
        startPush();
        drainWriteQueue();
        afterConnect();
    }

    /**
     * Resolve the BLE device name. The advertised name frequently arrives empty at connect time; once
     * the GATT is up we can usually read it from the live device or the remembered pref, publish it to
     * the parser (feeds JSON btName) and persist it.
     */
    private void ensureDeviceName(BluetoothGatt g) {
        try {
            if (deviceName == null || deviceName.isEmpty()) {
                if (g != null && g.getDevice() != null) {
                    try {
                        String n = g.getDevice().getName();
                        if (n != null && !n.isEmpty()) deviceName = n;
                    } catch (Throwable ignored) {}
                }
            }
            if (deviceName == null || deviceName.isEmpty()) {
                try {
                    String n = appCtx.getSharedPreferences("lb", Context.MODE_PRIVATE)
                            .getString("last_device_name", "");
                    if (n != null && !n.isEmpty()) deviceName = n;
                } catch (Throwable ignored) {}
            }
            if (deviceName != null && !deviceName.isEmpty()) {
                parser.btName = deviceName;
                try {
                    appCtx.getSharedPreferences("lb", Context.MODE_PRIVATE).edit()
                            .putString("last_device_name", deviceName).apply();
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            Log.e(TAG, "ensureDeviceName failed", t);
        }
    }

    private boolean readGapDeviceName(BluetoothGatt g) {
        try {
            if (g == null) return false;
            BluetoothGattService gap = g.getService(GAP_SERVICE);
            if (gap == null) return false;
            BluetoothGattCharacteristic c = gap.getCharacteristic(GAP_DEVICE_NAME);
            if (c == null) return false;
            if (!g.readCharacteristic(c)) return false;
            main.postDelayed(() -> { if (!charsSetupDone) setupCharacteristics(g); }, 1200);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "readGapDeviceName failed", t);
            return false;
        }
    }

    private void setupCharacteristics(BluetoothGatt g) {
        if (g == null) return;
        synchronized (this) {
            if (charsSetupDone) return;
            charsSetupDone = true;
        }
        BluetoothGattService svc = resolveService(g);
        if (svc == null) {
            Log.w(TAG, "no known SoFlow transport service found");
            pushState("no-service");
            return;
        }
        // "SoFlow" clear name gave no model: classify from the resolved transport (spec 1.5).
        if (activeProto == null) {
            String id = Models.protoFromTransport(usedTransport);
            activeProto = Models.get(id);
            Log.i(TAG, "classified from " + usedTransport.name() + " service -> " + id);
        }
        parser.setProto(activeProto);

        notifyChar = svc.getCharacteristic(UUID.fromString(usedTransport.notify));
        writeChar = svc.getCharacteristic(UUID.fromString(usedTransport.write));
        if (notifyChar == null || writeChar == null) {
            Log.w(TAG, "notify/write characteristic missing on " + usedTransport.name());
            pushState("no-char");
            return;
        }
        Log.i(TAG, "transport=" + usedTransport.name() + " service=" + usedTransport.service
                + " notify=" + notifyChar.getUuid() + " write=" + writeChar.getUuid());
        enableNotifications(g);
    }

    /**
     * Resolve the GATT service: try the classified model's expected transport first, then fall back
     * through nordic -> kingmeter -> so6 (spec 2). Remembers which transport won in {@link #usedTransport}.
     */
    private BluetoothGattService resolveService(BluetoothGatt g) {
        Transport want = activeProto != null ? activeProto.transport : null;
        if (want != null) {
            BluetoothGattService svc = g.getService(UUID.fromString(want.service));
            if (svc != null) { usedTransport = want; return svc; }
        }
        for (Transport cand : TRANSPORT_ORDER) {
            if (cand == want) continue;
            BluetoothGattService svc = g.getService(UUID.fromString(cand.service));
            if (svc != null) {
                usedTransport = cand;
                if (want != null) Log.i(TAG, "note: expected " + want.name() + " service absent, using " + cand.name());
                return svc;
            }
        }
        return null;
    }

    private void enableNotifications(BluetoothGatt g) {
        try {
            g.setCharacteristicNotification(notifyChar, true);
            BluetoothGattDescriptor cccd = notifyChar.getDescriptor(CCCD);
            if (cccd != null) {
                cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                boolean ok = g.writeDescriptor(cccd);
                Log.i(TAG, "writeDescriptor(CCCD) initiated=" + ok);
                if (!ok) main.post(() -> onNotifyReady(g));
            } else {
                Log.w(TAG, "CCCD descriptor missing; proceeding");
                main.post(() -> onNotifyReady(g));
            }
        } catch (Throwable t) {
            Log.e(TAG, "enableNotifications failed", t);
            main.post(() -> onNotifyReady(g));
        }
    }

    // ── Post-connect handshake (spec 5.8) ──

    private void afterConnect() {
        try {
            Proto p = activeProto;
            if (p == null) return;
            for (CommandBuilder.Frame f : CommandBuilder.afterConnect(p, settings)) enqueue(f);
            // SO4 encryption depends on firmware: send the init frame only once byte 12 reveals the
            // version. Arm a fallback so a unit that never pushes a version still ends up "connected".
            if (p.family == Family.D7 && "so4".equals(p.variant)) {
                pushState("linking");
                main.removeCallbacks(so4LinkTimeout);
                main.postDelayed(so4LinkTimeout, SO4_LINK_TIMEOUT_MS);
            }
        } catch (Throwable t) {
            Log.e(TAG, "afterConnect failed", t);
        }
    }

    /** SO4 only: fire the version-gated init frame once, when the firmware version becomes known. */
    private void maybeSendSo4Init() {
        Proto p = activeProto;
        if (p == null || initSent) return;
        if (!(p.family == Family.D7 && "so4".equals(p.variant))) return;
        if (settings.fwMajor == null) return;
        initSent = true;
        main.removeCallbacks(so4LinkTimeout);
        enqueue(CommandBuilder.so4InitAfterVersion(p, settings));
        pushState("connected");
    }

    private final Runnable so4LinkTimeout = new Runnable() {
        @Override
        public void run() {
            if (!connected || initSent) return;
            Log.i(TAG, "SO4 version-wait timed out; proceeding plaintext");
            pushState("connected");
        }
    };

    // ── Live-data push (~2x/s) ──

    private final Runnable pushTask = new Runnable() {
        @Override
        public void run() {
            if (!connected) return;
            try {
                if (listener != null) listener.onLiveData(parser.toJson());
            } catch (Throwable t) {
                Log.e(TAG, "push failed", t);
            }
            try { if (gatt != null) gatt.readRemoteRssi(); } catch (Throwable ignored) {}
            main.postDelayed(this, PUSH_INTERVAL_MS);
        }
    };

    private void startPush() {
        main.removeCallbacks(pushTask);
        main.postDelayed(pushTask, PUSH_INTERVAL_MS);
    }

    private void stopPush() {
        main.removeCallbacks(pushTask);
    }

    // ── Write queue (serialised GATT writes, spec 7.1) ──

    private void enqueue(CommandBuilder.Frame frame) {
        if (frame == null || frame.bytes == null) return;
        synchronized (writeQueue) { writeQueue.add(frame); }
        drainWriteQueue();
    }

    private void drainWriteQueue() {
        if (!notifyReady) return;
        CommandBuilder.Frame frame;
        synchronized (writeQueue) {
            if (writing) return;
            frame = writeQueue.poll();
            if (frame == null) return;
            writing = true;
        }
        boolean started = doWrite(frame);
        if (started) {
            if (frame.ackKey != null) armAck(frame.ackKey);
        } else {
            synchronized (writeQueue) { writing = false; }
            main.postDelayed(this::drainWriteQueue, WRITE_SETTLE_MS);
        }
    }

    /** Encrypt per model policy (spec 3.4) and write. Prefers WRITE_TYPE_NO_RESPONSE (spec 7.1). */
    private boolean doWrite(CommandBuilder.Frame frame) {
        try {
            BluetoothGatt g = gatt;
            BluetoothGattCharacteristic wc = writeChar;
            if (g == null || wc == null || frame.bytes == null) return false;
            byte[] out = frame.bytes;
            Proto p = activeProto;
            if (p != null && Models.encActive(p, settings)) {
                byte[] key = Models.encKey(p);
                if (key != null && Crypto.OK) {
                    byte[] enc = Crypto.encrypt(out, key);
                    if (enc != null) out = enc;
                }
            }
            int props = wc.getProperties();
            if ((props & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                wc.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            } else if ((props & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
                wc.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            }
            wc.setValue(out);
            return g.writeCharacteristic(wc);
        } catch (Throwable t) {
            Log.e(TAG, "doWrite failed", t);
            return false;
        }
    }

    // ── Ack tracking (spec 7.2) ──

    private void armAck(String key) {
        if (key == null) return;
        Runnable prev = pendingAcks.remove(key);
        if (prev != null) main.removeCallbacks(prev);
        Runnable timeout = () -> {
            pendingAcks.remove(key);
            Log.i(TAG, "no confirmation for " + key + " within " + ACK_TIMEOUT_MS + "ms");
        };
        pendingAcks.put(key, timeout);
        main.postDelayed(timeout, ACK_TIMEOUT_MS);
    }

    private void resolveAck(String key) {
        if (key == null) return;
        Runnable timeout = pendingAcks.remove(key);
        if (timeout != null) {
            main.removeCallbacks(timeout);
            Log.i(TAG, "confirmed: " + key);
        }
    }

    private void clearAcks() {
        for (Runnable r : pendingAcks.values()) main.removeCallbacks(r);
        pendingAcks.clear();
    }

    // ── High-level commands (from the JS bridge) ──

    private void send(CommandBuilder.Frame f) {
        if (f == null) return;   // command unsupported on the active model
        enqueue(f);
    }

    /** Speed lock: true unlocks (immobiliser off), false locks. */
    void setLock(boolean unlocked) {
        Proto p = activeProto;
        if (p == null) return;
        try {
            settings.speedUnlocked = unlocked;
            send(unlocked ? CommandBuilder.unlock(p, settings) : CommandBuilder.lock(p, settings));
        } catch (Throwable t) {
            Log.e(TAG, "setLock failed", t);
        }
    }

    /** Set the tuning max speed (km/h). No-op on families without a speed command (SO6/SO4 UL). */
    void setMaxSpeed(double kmh) {
        Proto p = activeProto;
        if (p == null) return;
        try { send(CommandBuilder.setMaxSpeed(p, settings, kmh)); }
        catch (Throwable t) { Log.e(TAG, "setMaxSpeed failed", t); }
    }

    /** Ride mode: eco 0, normal 1, sport 2. */
    void setSpeedMode(int mode) {
        Proto p = activeProto;
        if (p == null) return;
        try { send(CommandBuilder.setSpeedMode(p, settings, mode)); }
        catch (Throwable t) { Log.e(TAG, "setSpeedMode failed", t); }
    }

    /** Display unit: true imperial (mph), false metric (km/h). */
    void setUnit(boolean imperial) {
        Proto p = activeProto;
        if (p == null) return;
        try { send(CommandBuilder.setUnit(p, settings, imperial)); }
        catch (Throwable t) { Log.e(TAG, "setUnit failed", t); }
    }

    /** Battery unlock (D7 only; SO4 only from V52). No-op otherwise. */
    void batteryUnlock() {
        Proto p = activeProto;
        if (p == null) return;
        try { send(CommandBuilder.batteryUnlock(p, settings)); }
        catch (Throwable t) { Log.e(TAG, "batteryUnlock failed", t); }
    }

    /** Front light on/off (so5base). */
    void setFrontLight(boolean on) {
        try { send(CommandBuilder.frontLight(on)); }
        catch (Throwable t) { Log.e(TAG, "setFrontLight failed", t); }
    }

    /** Dark mode on/off (so5base). */
    void setDarkMode(boolean on) {
        try { send(CommandBuilder.darkMode(on)); }
        catch (Throwable t) { Log.e(TAG, "setDarkMode failed", t); }
    }

    /** Zero-start on/off (so5base). */
    void setZeroStart(boolean on) {
        try { send(CommandBuilder.zeroStart(on)); }
        catch (Throwable t) { Log.e(TAG, "setZeroStart failed", t); }
    }

    /** Turn/indicator light (so4 path). */
    void setIndicator(boolean on) {
        Proto p = activeProto;
        if (p == null) return;
        try { send(CommandBuilder.indicator(p, settings, on)); }
        catch (Throwable t) { Log.e(TAG, "setIndicator failed", t); }
    }

    // ── State reporting ──

    private void pushState(String status) {
        try {
            JSONObject o = new JSONObject();
            o.put("connected", connected);
            o.put("name", deviceName == null ? "" : deviceName);
            o.put("address", desiredAddress == null ? "" : desiredAddress);
            o.put("status", status == null ? "" : status);
            Proto p = activeProto;
            if (p != null) {
                o.put("model", p.name);
                o.put("family", p.family.name());
                // Per-model capabilities so the dashboard shows only the settings this model supports.
                boolean so5base = (p.family == Family.D7 && "so5base".equals(p.variant));
                JSONObject caps = new JSONObject();
                caps.put("speed", Models.speedSupported(p, settings));
                caps.put("mode", p.speed);
                caps.put("vlock", true);
                caps.put("battery", Models.batterySupported(p, settings));
                caps.put("frontLight", so5base);
                caps.put("darkMode", so5base);
                caps.put("zeroStart", so5base);
                caps.put("unit", so5base || p.family == Family.SO3);
                caps.put("indicator", "so4".equals(p.variant));
                // Telemetry-value caps: show a live tile only where the value is proven (decompiled
                // manufacturer app, per-model matrix). voltage is parsed by every family; current is
                // parsed but shown on no user-facing screen (dev/admin only) -> never a tile; power and
                // energy are frame-parsed only by SO3; total/error/lock come from the D7/SO3 frames and
                // are absent on SO6. trip is intentionally not shown: the reference app never surfaces it
                // (both mileage labels bind the total), so there is no trip tile at all.
                boolean d7 = (p.family == Family.D7);
                caps.put("voltage", true);
                caps.put("current", false);
                caps.put("power", p.family == Family.SO3);
                caps.put("energy", p.family == Family.SO3);
                caps.put("total", p.family != Family.SO6);
                caps.put("error", d7);
                caps.put("lock", d7);
                o.put("caps", caps);
            }
            if (listener != null) listener.onState(o.toString());
        } catch (Throwable t) {
            Log.e(TAG, "pushState failed", t);
        }
    }

    void shutdown() {
        stopScan();
        disconnect();
    }
}
