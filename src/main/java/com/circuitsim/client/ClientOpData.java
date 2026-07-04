package com.circuitsim.client;

import com.circuitsim.network.OperatingPointPacket;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Client-only store for the operating-point data from the most recent .OP run,
 * plus the player's per-device-type choice of which params to annotate.
 *
 * <p>Lifecycle is deliberately session-only: the device {@link #data} is
 * replaced on every .OP run (and never written to disk), and the per-type
 * {@link #selection} choices live only for the game session. Pressing K opens
 * the annotation menu, which reads/writes this class; the block renderer reads
 * {@link #isAnnotationActive()} + {@link #opFor(BlockPos)} to decide what to
 * float over each device.
 *
 * <p>Single-player runs the integrated server on its own thread, so the packet
 * handler may touch this off the render thread; all maps are guarded by the
 * class monitor.
 */
public final class ClientOpData {

    private ClientOpData() {}

    /** Max params annotated above one device (and slots in the edit menu). */
    public static final int MAX_SLOTS = 4;

    /** One device's operating point, as received from the server. */
    public static final class DeviceOp {
        public final String typeKey;
        public final String label;
        public final LinkedHashMap<String, Double> params;

        DeviceOp(String typeKey, String label, LinkedHashMap<String, Double> params) {
            this.typeKey = typeKey;
            this.label   = label;
            this.params  = params;
        }
    }

    private static final Object LOCK = new Object();

    /** One swept value's device operating points. */
    private static final class Frame {
        final String label;
        final Map<BlockPos, DeviceOp> data;
        /** Subcircuit-block pos → its internal devices' OPs, for the mini-projection. */
        final Map<BlockPos, List<OperatingPointPacket.SubDevice>> projections;
        Frame(String label, Map<BlockPos, DeviceOp> data,
              Map<BlockPos, List<OperatingPointPacket.SubDevice>> projections) {
            this.label = label;
            this.data  = data;
            this.projections = projections;
        }
    }

    /** Frames from the latest .OP run — one for a plain run, one per swept value. */
    private static final List<Frame> frames = new ArrayList<>();
    /** Which frame the annotation currently shows. */
    private static int currentFrame = 0;
    /** typeKey → display label, in first-seen order. */
    private static final Map<String, String> typeLabels = new LinkedHashMap<>();
    /** typeKey → union of available param names, in first-seen order. */
    private static final Map<String, List<String>> available = new LinkedHashMap<>();
    /** typeKey → chosen params (length {@link #MAX_SLOTS}, nulls = empty slot). */
    private static final Map<String, String[]> selection = new LinkedHashMap<>();

    private static boolean annotationActive = false;

    // ------------------------------------------------------------------------
    // Ingest
    // ------------------------------------------------------------------------

    /** Replaces the stored data with a fresh .OP run's frames. */
    public static void setData(List<OperatingPointPacket.Frame> pktFrames) {
        synchronized (LOCK) {
            frames.clear();
            typeLabels.clear();
            available.clear();
            currentFrame = 0;

            for (OperatingPointPacket.Frame pf : pktFrames) {
                Map<BlockPos, DeviceOp> map = new LinkedHashMap<>();
                for (OperatingPointPacket.Entry e : pf.entries) {
                    map.put(e.pos, new DeviceOp(e.typeKey, e.label, e.params));
                    registerType(e.typeKey, e.label, e.params);
                }
                // Subcircuit projections: register their internal devices' types
                // too, so chosenParams() yields a default selection for device
                // kinds that exist only inside a subcircuit.
                Map<BlockPos, List<OperatingPointPacket.SubDevice>> proj = new LinkedHashMap<>();
                for (OperatingPointPacket.SubProjection sp : pf.projections) {
                    proj.put(sp.parentPos, sp.devices);
                    for (OperatingPointPacket.SubDevice d : sp.devices) {
                        registerType(d.typeKey, d.label, d.params);
                    }
                }
                frames.add(new Frame(pf.label, map, proj));
            }

            // Keep selections for types that survived; default any new type, and
            // drop selections for types no longer present.
            selection.keySet().retainAll(typeLabels.keySet());
            for (String typeKey : typeLabels.keySet()) {
                selection.computeIfAbsent(typeKey, k -> defaultSelection(k, available.get(k)));
            }

            // If the new run has no annotatable devices, drop out of annotate mode
            // so a stale toggle doesn't leave the world looking broken.
            if (!hasDataLocked()) annotationActive = false;
        }
    }

    /** Records a device type's label + the union of its param names (first-seen order). */
    private static void registerType(String typeKey, String label,
                                     Map<String, Double> params) {
        typeLabels.putIfAbsent(typeKey, label);
        List<String> av = available.computeIfAbsent(typeKey, k -> new ArrayList<>());
        for (String p : params.keySet()) {
            if (!av.contains(p)) av.add(p);
        }
    }

    private static boolean hasDataLocked() {
        for (Frame f : frames) if (!f.data.isEmpty()) return true;
        return false;
    }

    // ------------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------------

    public static boolean hasData() {
        synchronized (LOCK) { return hasDataLocked(); }
    }

    public static DeviceOp opFor(BlockPos pos) {
        synchronized (LOCK) {
            if (currentFrame < 0 || currentFrame >= frames.size()) return null;
            return frames.get(currentFrame).data.get(pos);
        }
    }

    /**
     * Internal-device OPs for the subcircuit block at {@code parentPos} in the
     * current frame — what the mini-circuit projection floats over each device.
     * Empty when this run produced no projection for that subcircuit.
     */
    public static List<OperatingPointPacket.SubDevice> subDevicesFor(BlockPos parentPos) {
        synchronized (LOCK) {
            if (currentFrame < 0 || currentFrame >= frames.size()) return List.of();
            List<OperatingPointPacket.SubDevice> list =
                    frames.get(currentFrame).projections.get(parentPos);
            return list == null ? List.of() : list;
        }
    }

    // ------------------------------------------------------------------------
    // Sweep frame navigation
    // ------------------------------------------------------------------------

    /** Number of swept-value frames (1 for a plain .OP run). */
    public static int frameCount() {
        synchronized (LOCK) { return frames.size(); }
    }

    public static int currentFrameIndex() {
        synchronized (LOCK) { return currentFrame; }
    }

    /** Label of the current frame ("" for a plain run). */
    public static String currentFrameLabel() {
        synchronized (LOCK) {
            if (currentFrame < 0 || currentFrame >= frames.size()) return "";
            return frames.get(currentFrame).label;
        }
    }

    /** Steps the current frame by {@code dir} (wrapping); no-op if ≤1 frame. */
    public static void cycleFrame(int dir) {
        synchronized (LOCK) {
            if (frames.size() <= 1) return;
            currentFrame = ((currentFrame + dir) % frames.size() + frames.size()) % frames.size();
        }
    }

    public static boolean isAnnotationActive() {
        synchronized (LOCK) { return annotationActive; }
    }

    public static void setAnnotationActive(boolean active) {
        synchronized (LOCK) { annotationActive = active; }
    }

    /** typeKeys present in the latest run, in first-seen order. */
    public static List<String> types() {
        synchronized (LOCK) { return new ArrayList<>(typeLabels.keySet()); }
    }

    public static String labelFor(String typeKey) {
        synchronized (LOCK) { return typeLabels.getOrDefault(typeKey, typeKey); }
    }

    public static List<String> availableParams(String typeKey) {
        synchronized (LOCK) {
            List<String> av = available.get(typeKey);
            return av == null ? List.of() : new ArrayList<>(av);
        }
    }

    /** The chosen param for {@code slot} (0..MAX_SLOTS-1), or null if empty. */
    public static String slot(String typeKey, int slot) {
        synchronized (LOCK) {
            String[] sel = selection.get(typeKey);
            if (sel == null || slot < 0 || slot >= sel.length) return null;
            return sel[slot];
        }
    }

    /** Sets (or clears, with {@code param == null}) one slot for a device type. */
    public static void setSlot(String typeKey, int slot, String param) {
        synchronized (LOCK) {
            if (slot < 0 || slot >= MAX_SLOTS) return;
            String[] sel = selection.computeIfAbsent(typeKey, k -> new String[MAX_SLOTS]);
            sel[slot] = (param == null || param.isEmpty()) ? null : param;
        }
    }

    /**
     * The ordered, non-empty params chosen for {@code typeKey} — what the
     * renderer floats over a device of that type.
     */
    public static List<String> chosenParams(String typeKey) {
        synchronized (LOCK) {
            String[] sel = selection.get(typeKey);
            List<String> out = new ArrayList<>();
            if (sel != null) {
                for (String p : sel) if (p != null) out.add(p);
            }
            return out;
        }
    }

    // ------------------------------------------------------------------------
    // Defaults
    // ------------------------------------------------------------------------

    private static String[] defaultSelection(String typeKey, List<String> av) {
        String[] sel = new String[MAX_SLOTS];
        if (av == null || av.isEmpty()) return sel;

        List<String> picks = new ArrayList<>();
        for (String pref : preferredParams(typeKey)) {
            if (av.contains(pref) && !picks.contains(pref)) picks.add(pref);
            if (picks.size() >= MAX_SLOTS) break;
        }
        // Backfill from whatever's available so a device always shows something.
        for (String p : av) {
            if (picks.size() >= MAX_SLOTS) break;
            if (!picks.contains(p)) picks.add(p);
        }
        for (int i = 0; i < picks.size() && i < MAX_SLOTS; i++) sel[i] = picks.get(i);
        return sel;
    }

    /** Preferred (and ordered) default params for a device category. */
    private static List<String> preferredParams(String typeKey) {
        String t = typeKey == null ? "" : typeKey;
        if (t.contains("nmos") || t.contains("pmos"))
            return List.of("id", "vgs", "vds", "gm", "vth", "von", "vdsat", "gds");
        if (t.contains("npn") || t.contains("pnp"))
            return List.of("ic", "ib", "vbe", "vce", "gm", "vbc");
        if (t.equals("diode"))            return List.of("id", "vd", "gd");
        if (t.contains("resistor"))       return List.of("i", "p", "resistance");
        if (t.contains("capacitor"))      return List.of("i", "capacitance");
        if (t.equals("inductor"))         return List.of("i", "inductance");
        // transformer OP data comes from its primary winding's L device
        if (t.equals("transformer"))      return List.of("i", "inductance");
        if (t.equals("voltage_source"))   return List.of("i", "p");
        if (t.equals("current_source"))   return List.of("v", "p");
        if (t.equals("vswitch"))          return List.of("i", "p");
        // behavioral / controlled sources and anything else
        return List.of("i", "v", "p");
    }
}
