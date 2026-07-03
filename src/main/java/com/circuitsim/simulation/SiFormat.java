package com.circuitsim.simulation;

/**
 * SI-suffixed engineering formatting ("0.00001" → "10u", "158777.6" →
 * "158.7776k") — the display format used everywhere in the mod. Lives in the
 * simulation package (no Minecraft imports) so MC-free code like
 * {@link NgSpiceRunner} can use it; {@code ComponentEditScreen.formatValue}
 * delegates here so the many existing screen/packet call sites are unchanged.
 */
public final class SiFormat {

    private SiFormat() {}

    private static final double[][] TIERS = {
        { 1e12,  1e15  },
        { 1e9,   1e12  },
        { 1e6,   1e9   },
        { 1e3,   1e6   },
        { 1e0,   1e3   },
        { 1e-3,  1e0   },
        { 1e-6,  1e-3  },
        { 1e-9,  1e-6  },
        { 1e-12, 1e-9  },
        { 1e-15, 1e-12 },
    };
    private static final String[] NAMES = { "T", "G", "Meg", "k", "", "m", "u", "n", "p", "f" };

    public static String value(double val) {
        if (val == 0.0) return "0";

        double abs = Math.abs(val);
        for (int i = 0; i < TIERS.length; i++) {
            if (abs >= TIERS[i][0] && abs < TIERS[i][1]) {
                double scaled = val / TIERS[i][0];
                // Locale.ROOT: a comma-decimal system locale would otherwise
                // print "10,000000" — and defeat trimTrailingZeros, which
                // looks for '.'.
                return trimTrailingZeros(
                        String.format(java.util.Locale.ROOT, "%.6f", scaled)) + NAMES[i];
            }
        }
        return String.valueOf(val);
    }

    public static String trimTrailingZeros(String s) {
        if (!s.contains(".")) return s;
        s = s.replaceAll("0+$", "");
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s;
    }
}
