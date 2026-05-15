package com.circuitsim.simulation;

import java.util.*;

/**
 * Server-side store for parametric sweep results.
 * Keyed by an auto-incrementing session ID embedded in clickable chat links.
 * Capped at 10 sessions to avoid unbounded memory growth.
 */
public class ParametricResultCache {

    public static class ResultSet {
        public final String              sweepComponentName;
        public final String              sweepUnit;
        public final List<Double>        sweepValues;
        /** probeName → one value per (valid) sweep point, in order */
        public final Map<String, List<Double>> probeVoltages;
        public final Map<String, List<Double>> probeCurrents;
        /**
         * Optional per-probe Y-axis unit override. When a probe name has an entry
         * here, {@link #getUnit} returns that string instead of the default
         * {@code "V"} / {@code "A"} from the bucket the probe lives in. Used by
         * user-defined {@code plot} directives that compute things like {@code db}
         * or unitless ratios.
         */
        public final Map<String, String> probeUnits;
        /**
         * When true the sweep axis is a frequency axis and GraphScreen
         * should use a logarithmic X scale.
         */
        public final boolean isLogFrequency;
        /**
         * Lines of formatted output (the same text written to the result book).
         * Optional — populated so {@code /circuitsim output <id>} can re-open
         * the {@link com.circuitsim.screen.SimulationOutputScreen} on demand
         * without re-running the simulation. Empty list if not stored.
         */
        public List<String> outputLines = java.util.Collections.emptyList();
        /** Display title for the output viewer when reopened. */
        public String       outputTitle = "";

        public ResultSet(String sweepComponentName, String sweepUnit,
                         List<Double> sweepValues,
                         Map<String, List<Double>> probeVoltages,
                         Map<String, List<Double>> probeCurrents,
                         boolean isLogFrequency) {
            this(sweepComponentName, sweepUnit, sweepValues,
                    probeVoltages, probeCurrents, null, isLogFrequency);
        }

        public ResultSet(String sweepComponentName, String sweepUnit,
                         List<Double> sweepValues,
                         Map<String, List<Double>> probeVoltages,
                         Map<String, List<Double>> probeCurrents,
                         Map<String, String> probeUnits,
                         boolean isLogFrequency) {
            this.sweepComponentName = sweepComponentName;
            this.sweepUnit          = sweepUnit;
            this.sweepValues        = Collections.unmodifiableList(new ArrayList<>(sweepValues));
            this.probeVoltages      = Collections.unmodifiableMap(new LinkedHashMap<>(probeVoltages));
            this.probeCurrents      = Collections.unmodifiableMap(new LinkedHashMap<>(probeCurrents));
            this.probeUnits         = probeUnits == null
                    ? Collections.emptyMap()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(probeUnits));
            this.isLogFrequency     = isLogFrequency;
        }

        /** Ordered list of all probe names (voltage first, then current). */
        public List<String> getAllProbeNames() {
            List<String> names = new ArrayList<>();
            names.addAll(probeVoltages.keySet());
            names.addAll(probeCurrents.keySet());
            return names;
        }

        /** Returns the value list for the named probe, or null if not found. */
        public List<Double> getValues(String probeName) {
            if (probeVoltages.containsKey(probeName)) return probeVoltages.get(probeName);
            return probeCurrents.get(probeName);
        }

        public boolean isVoltage(String probeName) {
            return probeVoltages.containsKey(probeName);
        }

        /**
         * Y-axis unit for the given probe. Honours {@link #probeUnits} overrides
         * (used for user-plot ratios, dB, phase, etc.); falls back to {@code "V"}
         * for voltage-bucket probes and {@code "A"} for current-bucket probes.
         */
        public String getUnit(String probeName) {
            String override = probeUnits.get(probeName);
            if (override != null) return override;
            if (probeVoltages.containsKey(probeName)) return "V";
            if (probeCurrents.containsKey(probeName)) return "A";
            return "";
        }
    }

    private static final int MAX_SESSIONS = 10;

    private static final LinkedHashMap<Integer, ResultSet> cache = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, ResultSet> eldest) {
            return size() > MAX_SESSIONS;
        }
    };

    private static int nextId = 0;

    public static synchronized int store(ResultSet rs) {
        int id = nextId++;
        cache.put(id, rs);
        return id;
    }

    public static synchronized ResultSet get(int id) {
        return cache.get(id);
    }
}