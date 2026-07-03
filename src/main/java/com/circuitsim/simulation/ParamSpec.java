package com.circuitsim.simulation;

import com.circuitsim.screen.ComponentEditScreen;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Parser for the Param block's text. One variable per non-empty line:
 * <pre>
 *   name = value          (optionally wrapped in parentheses)
 * </pre>
 * where {@code value} is either
 * <ul>
 *   <li><b>scalar</b> — a number with SI suffix or plain ({@code 1k},
 *       {@code 4.7u}, {@code 1e-3}),</li>
 *   <li><b>range sweep</b> — {@code start:stop:step} (inclusive of stop where
 *       it lands on a step),</li>
 *   <li><b>list sweep</b> — {@code v1,v2,v3,...}, or</li>
 *   <li><b>distribution</b> — an ngspice statistical function call
 *       ({@code gauss(nom, rel, sigma)}, {@code agauss(nom, abs, sigma)},
 *       {@code unif(nom, rel)}, {@code aunif(nom, abs)},
 *       {@code limit(nom, abs)}; manual ch. 18.2), passed through verbatim as
 *       a {@code .param} line and re-rolled per Monte Carlo run by
 *       {@code mc_source}.</li>
 * </ul>
 * At most one variable in a circuit may be a sweep; the per-block check here
 * reports both offenders by name, and the server repeats the check across
 * blocks at simulation time. All parse errors carry their 1-based line number
 * so the edit screen can point at the broken line.
 */
public final class ParamSpec {

    /** Matches the ngspice statistical functions usable inside {@code .param}. */
    private static final java.util.regex.Pattern DIST =
            java.util.regex.Pattern.compile("(?i)^(a?gauss|a?unif|limit)\\s*\\(.*\\)$");

    /** True when {@code value} is a distribution call like {@code gauss(1k, 0.05, 3)}. */
    public static boolean isDistribution(String value) {
        return value != null && DIST.matcher(value.strip()).matches();
    }

    /** Maximum points one sweep may expand to (matches the legacy limit). */
    public static final int MAX_SWEEP_VALUES = 50;

    public static final class Entry {
        public final int          lineNo;     // 1-based line in the block text
        public final String       name;
        public final String       rawValue;   // value text exactly as typed
        public final boolean      isSweep;
        /** Distribution call passed through verbatim; {@link #values} is empty. */
        public final boolean      isDist;
        public final List<Double> values;     // 1 value for scalars, N for sweeps

        Entry(int lineNo, String name, String rawValue, boolean isSweep,
              boolean isDist, List<Double> values) {
            this.lineNo   = lineNo;
            this.name     = name;
            this.rawValue = rawValue;
            this.isSweep  = isSweep;
            this.isDist   = isDist;
            this.values   = values;
        }
    }

    public static final class ParseResult {
        public final List<Entry>  entries = new ArrayList<>();
        public final List<String> errors  = new ArrayList<>();
        public boolean ok() { return errors.isEmpty(); }
    }

    private ParamSpec() {}

    public static ParseResult parse(String text) {
        ParseResult out = new ParseResult();
        if (text == null) return out;

        Set<String> seen = new LinkedHashSet<>();
        String sweepName = null;
        String[] lines = text.split("\\r?\\n", -1);
        for (int i = 0; i < lines.length; i++) {
            int lineNo = i + 1;
            String line = lines[i].strip();
            if (line.isEmpty()) continue;

            // Accept the parenthesised form "(name = value)".
            if (line.startsWith("(") && line.endsWith(")")) {
                line = line.substring(1, line.length() - 1).strip();
                if (line.isEmpty()) continue;
            }

            int eq = line.indexOf('=');
            if (eq < 0) {
                out.errors.add("Line " + lineNo + ": expected 'name = value'");
                continue;
            }
            String name  = line.substring(0, eq).strip();
            String value = line.substring(eq + 1).strip();

            if (!ComponentEditScreen.isIdentifier(name)) {
                out.errors.add("Line " + lineNo + ": '" + name
                        + "' is not a valid identifier");
                continue;
            }
            String nameKey = name.toLowerCase(java.util.Locale.ROOT);
            if (!seen.add(nameKey)) {
                out.errors.add("Line " + lineNo + ": duplicate variable '" + name + "'");
                continue;
            }
            if (value.isEmpty()) {
                out.errors.add("Line " + lineNo + ": missing value for '" + name + "'");
                continue;
            }

            if (isDistribution(value)) {
                out.entries.add(new Entry(lineNo, name, value, false, true, List.of()));
                continue;
            }

            List<Double> values;
            boolean sweep;
            try {
                if (value.contains(":")) {
                    values = parseRange(value);
                    sweep  = true;
                } else if (value.contains(",")) {
                    values = parseList(value);
                    sweep  = values.size() > 1;
                } else {
                    values = List.of(ComponentEditScreen.parseSI(value));
                    sweep  = false;
                }
            } catch (IllegalArgumentException e) {   // includes NumberFormatException
                out.errors.add("Line " + lineNo + ": " + e.getMessage());
                continue;
            }
            if (values.isEmpty()) {
                out.errors.add("Line " + lineNo + ": no values for '" + name + "'");
                continue;
            }
            if (values.size() > MAX_SWEEP_VALUES) {
                out.errors.add("Line " + lineNo + ": sweep for '" + name + "' has "
                        + values.size() + " values; max " + MAX_SWEEP_VALUES);
                continue;
            }

            if (sweep) {
                if (sweepName != null) {
                    out.errors.add("Line " + lineNo + ": only ONE variable may sweep — '"
                            + sweepName + "' already does, so '" + name
                            + "' must be a single value");
                    continue;
                }
                sweepName = name;
            }
            out.entries.add(new Entry(lineNo, name, value, sweep, false, values));
        }
        return out;
    }

    private static List<Double> parseRange(String value) {
        String[] parts = value.split(":");
        if (parts.length != 3) {
            throw new IllegalArgumentException("range must be start:stop:step");
        }
        double start = ComponentEditScreen.parseSI(parts[0].strip());
        double stop  = ComponentEditScreen.parseSI(parts[1].strip());
        double step  = ComponentEditScreen.parseSI(parts[2].strip());
        if (step == 0) throw new IllegalArgumentException("step cannot be zero");
        if (step > 0 && stop < start || step < 0 && stop > start) {
            throw new IllegalArgumentException("step direction never reaches stop");
        }
        List<Double> vals = new ArrayList<>();
        double eps = 1e-10 * Math.abs(step);
        if (step > 0) {
            for (double v = start; v <= stop + eps && vals.size() <= MAX_SWEEP_VALUES; v += step) vals.add(v);
        } else {
            for (double v = start; v >= stop - eps && vals.size() <= MAX_SWEEP_VALUES; v += step) vals.add(v);
        }
        return vals;
    }

    private static List<Double> parseList(String value) {
        List<Double> vals = new ArrayList<>();
        for (String tok : value.split(",")) {
            String t = tok.strip();
            if (t.isEmpty()) continue;
            try {
                vals.add(ComponentEditScreen.parseSI(t));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("cannot parse '" + t + "'");
            }
            if (vals.size() > MAX_SWEEP_VALUES) break;
        }
        return vals;
    }
}
