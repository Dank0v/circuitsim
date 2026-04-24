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
         * When true the sweep axis is a frequency axis and GraphScreen
         * should use a logarithmic X scale.
         */
        public final boolean isLogFrequency;

        public ResultSet(String sweepComponentName, String sweepUnit,
                         List<Double> sweepValues,
                         Map<String, List<Double>> probeVoltages,
                         Map<String, List<Double>> probeCurrents,
                         boolean isLogFrequency) {
            this.sweepComponentName = sweepComponentName;
            this.sweepUnit          = sweepUnit;
            this.sweepValues        = Collections.unmodifiableList(new ArrayList<>(sweepValues));
            this.probeVoltages      = Collections.unmodifiableMap(new LinkedHashMap<>(probeVoltages));
            this.probeCurrents      = Collections.unmodifiableMap(new LinkedHashMap<>(probeCurrents));
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