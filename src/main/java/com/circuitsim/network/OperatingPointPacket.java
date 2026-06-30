package com.circuitsim.network;

import com.circuitsim.client.ClientOpData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * PLAY_TO_CLIENT: ships the per-device operating points harvested from an .OP
 * run (the {@code show <class>} tables) so the client can annotate them above
 * the devices.
 *
 * <p>Data is organised as one or more {@link Frame}s. A plain .OP run sends a
 * single frame; a parametric or temperature sweep sends one frame per swept
 * value, each labelled (e.g. {@code "W=2u"}, {@code "85C"}), so the player can
 * step through them in the K menu and watch the annotations change. The client
 * stores the lot in {@link ClientOpData}. An empty frame list clears any stale
 * annotation data.
 */
public class OperatingPointPacket {

    /** One device's operating point: where it is, what it is, and its params. */
    public static final class Entry {
        public final BlockPos pos;
        public final String   typeKey;   // groups devices in the edit menu
        public final String   label;     // human-readable type name
        public final LinkedHashMap<String, Double> params;

        public Entry(BlockPos pos, String typeKey, String label,
                     LinkedHashMap<String, Double> params) {
            this.pos     = pos;
            this.typeKey = typeKey;
            this.label   = label;
            this.params  = params;
        }
    }

    /**
     * One internal device of a subcircuit's OP projection: its position in the
     * blueprint-local frame (so the client can float its OP over the right cell
     * of the mini-circuit) plus the same typeKey/label/params an {@link Entry}
     * carries.
     */
    public static final class SubDevice {
        public final int dx, dy, dz;
        public final String typeKey;
        public final String label;
        public final LinkedHashMap<String, Double> params;

        public SubDevice(int dx, int dy, int dz, String typeKey, String label,
                         LinkedHashMap<String, Double> params) {
            this.dx = dx; this.dy = dy; this.dz = dz;
            this.typeKey = typeKey;
            this.label   = label;
            this.params  = params;
        }
    }

    /** One subcircuit instance's internal-device OPs, for its floating projection. */
    public static final class SubProjection {
        public final BlockPos parentPos;          // the Subcircuit block's anchor
        public final List<SubDevice> devices;

        public SubProjection(BlockPos parentPos, List<SubDevice> devices) {
            this.parentPos = parentPos;
            this.devices   = devices;
        }
    }

    /** One swept value's worth of device operating points. */
    public static final class Frame {
        public final String      label;   // "" for a plain OP run, else the value
        public final List<Entry> entries;
        /** Per-subcircuit internal-device OPs for the mini-circuit projections. */
        public final List<SubProjection> projections;

        public Frame(String label, List<Entry> entries) {
            this(label, entries, new ArrayList<>());
        }

        public Frame(String label, List<Entry> entries, List<SubProjection> projections) {
            this.label       = label == null ? "" : label;
            this.entries     = entries;
            this.projections = projections == null ? new ArrayList<>() : projections;
        }
    }

    private final List<Frame> frames;

    public OperatingPointPacket(List<Frame> frames) {
        this.frames = frames;
    }

    public OperatingPointPacket(FriendlyByteBuf buf) {
        int fn = buf.readInt();
        this.frames = new ArrayList<>(fn);
        for (int f = 0; f < fn; f++) {
            String fLabel = buf.readUtf(64);
            int n = buf.readInt();
            List<Entry> entries = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                BlockPos pos   = buf.readBlockPos();
                String typeKey = buf.readUtf(64);
                String label   = buf.readUtf(64);
                int pc         = buf.readInt();
                LinkedHashMap<String, Double> params = new LinkedHashMap<>();
                for (int p = 0; p < pc; p++) {
                    String name = buf.readUtf(64);
                    double val  = buf.readDouble();
                    params.put(name, val);
                }
                entries.add(new Entry(pos, typeKey, label, params));
            }
            // Subcircuit projections: one per instance, each a list of internal
            // devices positioned in the blueprint-local frame.
            int projCount = buf.readInt();
            List<SubProjection> projections = new ArrayList<>(projCount);
            for (int j = 0; j < projCount; j++) {
                BlockPos parentPos = buf.readBlockPos();
                int dn = buf.readInt();
                List<SubDevice> devices = new ArrayList<>(dn);
                for (int d = 0; d < dn; d++) {
                    int dx = buf.readInt(), dy = buf.readInt(), dz = buf.readInt();
                    String typeKey = buf.readUtf(64);
                    String label   = buf.readUtf(64);
                    int pc = buf.readInt();
                    LinkedHashMap<String, Double> params = new LinkedHashMap<>();
                    for (int p = 0; p < pc; p++) {
                        String name = buf.readUtf(64);
                        double val  = buf.readDouble();
                        params.put(name, val);
                    }
                    devices.add(new SubDevice(dx, dy, dz, typeKey, label, params));
                }
                projections.add(new SubProjection(parentPos, devices));
            }
            frames.add(new Frame(fLabel, entries, projections));
        }
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeInt(frames.size());
        for (Frame frame : frames) {
            buf.writeUtf(frame.label, 64);
            buf.writeInt(frame.entries.size());
            for (Entry e : frame.entries) {
                buf.writeBlockPos(e.pos);
                buf.writeUtf(e.typeKey, 64);
                buf.writeUtf(e.label, 64);
                // Cap at 64 params per device — far more than any ngspice device
                // exposes, but keeps a malformed packet bounded.
                int pc = Math.min(e.params.size(), 64);
                buf.writeInt(pc);
                int written = 0;
                for (var pe : e.params.entrySet()) {
                    if (written++ >= pc) break;
                    buf.writeUtf(pe.getKey(), 64);
                    buf.writeDouble(pe.getValue());
                }
            }
            buf.writeInt(frame.projections.size());
            for (SubProjection proj : frame.projections) {
                buf.writeBlockPos(proj.parentPos);
                buf.writeInt(proj.devices.size());
                for (SubDevice d : proj.devices) {
                    buf.writeInt(d.dx);
                    buf.writeInt(d.dy);
                    buf.writeInt(d.dz);
                    buf.writeUtf(d.typeKey, 64);
                    buf.writeUtf(d.label, 64);
                    int pc = Math.min(d.params.size(), 64);
                    buf.writeInt(pc);
                    int written = 0;
                    for (var pe : d.params.entrySet()) {
                        if (written++ >= pc) break;
                        buf.writeUtf(pe.getKey(), 64);
                        buf.writeDouble(pe.getValue());
                    }
                }
            }
        }
    }

    public static OperatingPointPacket decode(FriendlyByteBuf buf) {
        return new OperatingPointPacket(buf);
    }

    public List<Frame> frames() {
        return frames;
    }

    public void handle() {
        ClientOpData.setData(frames);
    }
}
