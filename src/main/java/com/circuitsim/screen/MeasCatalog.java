package com.circuitsim.screen;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Static catalog + code generator behind {@link MeasBuilderScreen}: the ngspice
 * {@code meas} genres (manual ch. 11.4, general forms 1-8), the control-language
 * expression functions (ch. 13.2), and multi-line measurement presets. Pure
 * data and string building — no Minecraft imports — so the generated syntax can
 * be exercised outside the game.
 *
 * <p>Everything generates <b>control-mode</b> syntax ({@code meas}, {@code let},
 * the mod's {@code plot NAME = EXPR} directive) because the Commands block is
 * spliced into a {@code .control} section, where the batch-only {@code param}
 * and {@code par('...')} forms of {@code .meas} don't work (manual 13.5.49) —
 * derived math must go through {@code let} instead.
 */
public final class MeasCatalog {

    private MeasCatalog() {}

    // ------------------------------------------------------------------------
    // Field / genre model
    // ------------------------------------------------------------------------

    public enum FieldKind {
        /** A vector: probe voltage, source current, or plot name. Gets a picker. */
        SIGNAL,
        /** Free text (value, SI number, or expression). */
        TEXT,
        /** RISE / FALL / CROSS choice. */
        EDGE,
        /** AVG / MAX / MIN / PP / RMS / MIN_AT / MAX_AT / INTEG choice. */
        STAT
    }

    public record Field(String key, String label, FieldKind kind,
                        boolean required, String def, String hint) {}

    /**
     * One {@code meas} form. {@link #tail} renders everything after
     * {@code meas <analysis> <name> } from the field values (blank = absent).
     */
    public record Genre(String id, String label, String desc,
                        List<Field> fields,
                        Function<Map<String, String>, String> tail) {}

    public static final String[] EDGE_OPTIONS = { "RISE", "FALL", "CROSS" };
    public static final String[] STAT_OPTIONS =
            { "AVG", "MAX", "MIN", "PP", "RMS", "MIN_AT", "MAX_AT", "INTEG" };
    public static final String[] ANALYSES = { "tran", "ac", "dc", "sp" };

    public static final List<Genre> GENRES = List.of(
        new Genre("trig_targ", "Trig → Targ",
                "Interval between two events (rise time, delay, period)",
                List.of(
                    new Field("tsig",  "Trig signal", FieldKind.SIGNAL, true,  "",     ""),
                    new Field("tval",  "Trig value",  FieldKind.TEXT,   true,  "",     "level, e.g. 0.9"),
                    new Field("tedge", "Trig edge",   FieldKind.EDGE,   false, "RISE", ""),
                    new Field("tn",    "Trig count",  FieldKind.TEXT,   false, "1",    "1, 2, … or LAST"),
                    new Field("gsig",  "Targ signal", FieldKind.SIGNAL, true,  "",     ""),
                    new Field("gval",  "Targ value",  FieldKind.TEXT,   true,  "",     ""),
                    new Field("gedge", "Targ edge",   FieldKind.EDGE,   false, "RISE", ""),
                    new Field("gn",    "Targ count",  FieldKind.TEXT,   false, "1",    "1, 2, … or LAST"),
                    new Field("td",    "Delay TD",    FieldKind.TEXT,   false, "",     "optional")),
                v -> {
                    StringBuilder sb = new StringBuilder();
                    sb.append("TRIG ").append(v.get("tsig"))
                      .append(" VAL=").append(v.get("tval"))
                      .append(' ').append(edge(v, "tedge", "tn"));
                    appendOpt(sb, "TD", v.get("td"));
                    sb.append(" TARG ").append(v.get("gsig"))
                      .append(" VAL=").append(v.get("gval"))
                      .append(' ').append(edge(v, "gedge", "gn"));
                    return sb.toString();
                }),

        new Genre("when", "When",
                "X-axis point where a signal crosses a value (or another signal)",
                List.of(
                    new Field("sig",  "Signal",     FieldKind.SIGNAL, true,  "",     ""),
                    new Field("val",  "Crosses",    FieldKind.TEXT,   true,  "",     "value or other signal"),
                    new Field("edge", "Edge",       FieldKind.EDGE,   false, "CROSS", ""),
                    new Field("n",    "Count",      FieldKind.TEXT,   false, "1",    "1, 2, … or LAST"),
                    new Field("td",   "Delay TD",   FieldKind.TEXT,   false, "",     "optional"),
                    new Field("from", "From",       FieldKind.TEXT,   false, "",     "optional"),
                    new Field("to",   "To",         FieldKind.TEXT,   false, "",     "optional")),
                v -> {
                    StringBuilder sb = new StringBuilder();
                    sb.append("WHEN ").append(v.get("sig")).append('=').append(v.get("val"))
                      .append(' ').append(edge(v, "edge", "n"));
                    appendOpt(sb, "TD", v.get("td"));
                    appendOpt(sb, "FROM", v.get("from"));
                    appendOpt(sb, "TO", v.get("to"));
                    return sb.toString();
                }),

        new Genre("find_when", "Find … When",
                "Value of one signal at the moment another crosses a value",
                List.of(
                    new Field("fsig", "Find signal", FieldKind.SIGNAL, true,  "",     ""),
                    new Field("wsig", "When signal", FieldKind.SIGNAL, true,  "",     ""),
                    new Field("val",  "Crosses",     FieldKind.TEXT,   true,  "",     "value or other signal"),
                    new Field("edge", "Edge",        FieldKind.EDGE,   false, "CROSS", ""),
                    new Field("n",    "Count",       FieldKind.TEXT,   false, "1",    "1, 2, … or LAST"),
                    new Field("td",   "Delay TD",    FieldKind.TEXT,   false, "",     "optional"),
                    new Field("from", "From",        FieldKind.TEXT,   false, "",     "optional"),
                    new Field("to",   "To",          FieldKind.TEXT,   false, "",     "optional")),
                v -> {
                    StringBuilder sb = new StringBuilder();
                    sb.append("FIND ").append(v.get("fsig"))
                      .append(" WHEN ").append(v.get("wsig")).append('=').append(v.get("val"))
                      .append(' ').append(edge(v, "edge", "n"));
                    appendOpt(sb, "TD", v.get("td"));
                    appendOpt(sb, "FROM", v.get("from"));
                    appendOpt(sb, "TO", v.get("to"));
                    return sb.toString();
                }),

        new Genre("find_at", "Find … At",
                "Value of a signal at a given time / frequency / sweep point",
                List.of(
                    new Field("fsig", "Find signal", FieldKind.SIGNAL, true, "", ""),
                    new Field("at",   "At",          FieldKind.TEXT,   true, "", "time (tran), freq (ac), V (dc)")),
                v -> "FIND " + v.get("fsig") + " AT=" + v.get("at")),

        new Genre("stat", "Statistic",
                "AVG / MAX / MIN / PP / RMS / MIN_AT / MAX_AT / INTEG over an interval",
                List.of(
                    new Field("stat", "Statistic",  FieldKind.STAT,   false, "MAX", ""),
                    new Field("sig",  "Signal",     FieldKind.SIGNAL, true,  "",    ""),
                    new Field("from", "From",       FieldKind.TEXT,   false, "",    "optional"),
                    new Field("to",   "To",         FieldKind.TEXT,   false, "",    "optional"),
                    new Field("td",   "Delay TD",   FieldKind.TEXT,   false, "",    "optional")),
                v -> {
                    StringBuilder sb = new StringBuilder();
                    sb.append(orDefault(v.get("stat"), "MAX")).append(' ').append(v.get("sig"));
                    appendOpt(sb, "FROM", v.get("from"));
                    appendOpt(sb, "TO", v.get("to"));
                    appendOpt(sb, "TD", v.get("td"));
                    return sb.toString();
                })
    );

    // NOTE: the manual also documents a DERIV genre (11.4.11), but ngspice-46
    // rejects it with "function 'deriv' currently not supported" (verified).
    // The working equivalent — let d = deriv(sig); meas FIND d AT=… — is
    // offered as the "Slope at point" preset instead.

    /** {@code meas <analysis> <name> <genre tail>} from the current field values. */
    public static String measLine(String analysis, String name, Genre g,
                                  Map<String, String> vals) {
        return "meas " + analysis + " " + name + " " + g.tail().apply(vals);
    }

    private static String edge(Map<String, String> v, String edgeKey, String countKey) {
        return orDefault(v.get(edgeKey), "CROSS") + "=" + orDefault(v.get(countKey), "1");
    }

    private static void appendOpt(StringBuilder sb, String kw, String val) {
        if (val != null && !val.isBlank()) sb.append(' ').append(kw).append('=').append(val.trim());
    }

    private static String orDefault(String v, String def) {
        return v == null || v.isBlank() ? def : v.trim();
    }

    // ------------------------------------------------------------------------
    // Validation
    // ------------------------------------------------------------------------

    private static final Pattern NAME_OK  = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*");
    private static final Pattern COUNT_OK = Pattern.compile("(?i)\\d+|last");

    /** First problem with the current inputs, or null when generatable. */
    public static String validate(String name, Genre g, Map<String, String> vals) {
        if (name == null || name.isBlank())        return "Missing result name.";
        if (!NAME_OK.matcher(name.trim()).matches())
            return "Result name must be a plain identifier (letters, digits, _).";
        for (Field f : g.fields()) {
            String v = vals.get(f.key());
            boolean blank = v == null || v.isBlank();
            if (f.required() && blank) return "Missing " + f.label() + ".";
            if (!blank && f.hint().contains("LAST")
                    && !COUNT_OK.matcher(v.trim()).matches())
                return f.label() + " must be an integer or LAST.";
        }
        return null;
    }

    /** Validation for the Functions tab: identifier name + non-blank argument. */
    public static String validateLet(String name, String arg) {
        if (name == null || name.isBlank())        return "Missing result name.";
        if (!NAME_OK.matcher(name.trim()).matches())
            return "Result name must be a plain identifier (letters, digits, _).";
        if (arg == null || arg.isBlank())          return "Missing argument.";
        return null;
    }

    // ------------------------------------------------------------------------
    // Expression functions (manual ch. 13.2)
    // ------------------------------------------------------------------------

    /**
     * One control-language function. {@code scalar} functions reduce a vector
     * to a number → emitted as {@code let} + {@code print}; vector functions
     * are emitted as the mod's graphable {@code plot NAME = EXPR} directive.
     */
    public record Func(String name, String doc, boolean scalar, String category) {}

    public static final List<Func> FUNCTIONS = List.of(
        new Func("db",          "20·log10(|x|) — gain in dB",                false, "AC / complex"),
        new Func("mag",         "|x| — magnitude (= abs)",                   false, "AC / complex"),
        new Func("ph",          "phase of complex x, radians",               false, "AC / complex"),
        new Func("cph",         "continuous phase, radians (no ±π wrap)",    false, "AC / complex"),
        new Func("unwrap",      "continuous phase, degrees in and out",      false, "AC / complex"),
        new Func("real",        "real part",                                 false, "AC / complex"),
        new Func("imag",        "imaginary part",                            false, "AC / complex"),
        new Func("conj",        "complex conjugate",                         false, "AC / complex"),
        new Func("group_delay", "-dφ/dω, seconds vs frequency",              false, "AC / complex"),
        new Func("deriv",       "d/dx vs the scale axis (numeric)",          false, "calculus"),
        new Func("integ",       "running integral vs the scale axis",        false, "calculus"),
        new Func("vecd",        "element-to-element differential",           false, "calculus"),
        new Func("mean",        "scalar mean of all elements",               true,  "reduce"),
        new Func("stddev",      "scalar standard deviation",                 true,  "reduce"),
        new Func("vecmax",      "scalar maximum element",                    true,  "reduce"),
        new Func("vecmin",      "scalar minimum element",                    true,  "reduce"),
        new Func("length",      "number of points in the vector",            true,  "reduce"),
        new Func("avg",         "running average (vector)",                  false, "reduce"),
        new Func("fft",         "fast Fourier transform",                    false, "transform"),
        new Func("ifft",        "inverse FFT",                               false, "transform"),
        new Func("abs",         "absolute value",                            false, "math"),
        new Func("sqrt",        "square root",                               false, "math"),
        new Func("ln",          "natural log",                               false, "math"),
        new Func("log10",       "log base 10",                               false, "math"),
        new Func("exp",         "e^x",                                       false, "math"),
        new Func("floor",       "round down",                                false, "math"),
        new Func("ceil",        "round up",                                  false, "math"),
        new Func("sin",         "sine",                                      false, "math"),
        new Func("cos",         "cosine",                                    false, "math"),
        new Func("tan",         "tangent",                                   false, "math"),
        new Func("atan",        "arc tangent",                               false, "math"),
        new Func("sgauss",      "gaussian random (μ=0, σ=1)",                false, "random"),
        new Func("sunif",       "uniform random in [-1, 1)",                 false, "random"),
        new Func("rnd",         "random integer in [0, |x|]",                false, "random")
    );

    /** Constants usable inside any expression (plot const, manual 13.2). */
    public static final String[][] CONSTANTS = {
        { "pi",      "3.14159…" },
        { "e",       "2.71828…" },
        { "c",       "speed of light, m/s" },
        { "boltz",   "k = 1.381e-23 J/K" },
        { "echarge", "q = 1.602e-19 C" },
        { "kelvin",  "-273.15 °C" },
        { "planck",  "h = 6.626e-34 J·s" },
    };

    /**
     * The line(s) for one applied function. Vector functions become a
     * graphable {@code plot} directive; scalar ones a {@code let} + {@code
     * print} pair so the value actually shows up in the output.
     */
    public static List<String> functionLines(Func fn, String name, String arg) {
        String call = fn.name() + "(" + arg.trim() + ")";
        if (fn.scalar()) {
            return List.of("let " + name.trim() + " = " + call,
                           "print " + name.trim());
        }
        return List.of("plot " + name.trim() + " = " + call);
    }

    // ------------------------------------------------------------------------
    // Presets
    // ------------------------------------------------------------------------

    public record Slot(String key, String label, FieldKind kind, String def, String hint) {}

    /**
     * A canned multi-line recipe. {@code ${key}} in template lines is replaced
     * with the slot's value. {@code analysis} is what the recipe expects the
     * Simulate block to run ("tran"/"ac"), shown as a hint in the UI.
     */
    public record Preset(String id, String label, String desc, String analysis,
                         List<Slot> slots, List<String> template) {}

    public static final List<Preset> PRESETS = List.of(
        new Preset("ac_report", "AC amplifier report",
                "DC gain, f3dB, GBW and phase margin from one AC sweep",
                "ac",
                List.of(new Slot("out",  "Output signal", FieldKind.SIGNAL, "", ""),
                        new Slot("fmin", "Low frequency", FieldKind.TEXT, "10", "a point on the flat band")),
                List.of("plot gain_db = db(${out})",
                        "plot gain_ph[deg] = cph(${out}) * 180/pi",
                        "meas ac dc_gain_db find gain_db at=${fmin}",
                        "let dc_gain = 10^(dc_gain_db/20)",
                        "meas ac f3db when gain_db=dc_gain_db-3",
                        "let gbw = dc_gain * f3db",
                        "meas ac ph_xover find gain_ph when gain_db=0",
                        "let pm = 180 + ph_xover",
                        "print dc_gain f3db gbw pm")),

        new Preset("rise_time", "Rise time",
                "Time from the low to the high threshold on a rising edge",
                "tran",
                List.of(new Slot("sig",   "Signal",     FieldKind.SIGNAL, "", ""),
                        new Slot("vlow",  "Low level",  FieldKind.TEXT, "", "e.g. 10% of swing"),
                        new Slot("vhigh", "High level", FieldKind.TEXT, "", "e.g. 90% of swing")),
                List.of("meas tran trise TRIG ${sig} VAL=${vlow} RISE=1 TARG ${sig} VAL=${vhigh} RISE=1")),

        new Preset("fall_time", "Fall time",
                "Time from the high to the low threshold on a falling edge",
                "tran",
                List.of(new Slot("sig",   "Signal",     FieldKind.SIGNAL, "", ""),
                        new Slot("vhigh", "High level", FieldKind.TEXT, "", "e.g. 90% of swing"),
                        new Slot("vlow",  "Low level",  FieldKind.TEXT, "", "e.g. 10% of swing")),
                List.of("meas tran tfall TRIG ${sig} VAL=${vhigh} FALL=1 TARG ${sig} VAL=${vlow} FALL=1")),

        new Preset("prop_delay", "Propagation delay",
                "Threshold crossing of the input to the same crossing of the output",
                "tran",
                List.of(new Slot("in",  "Input signal",  FieldKind.SIGNAL, "", ""),
                        new Slot("out", "Output signal", FieldKind.SIGNAL, "", ""),
                        new Slot("th",  "Threshold",     FieldKind.TEXT, "", "e.g. half the swing")),
                List.of("meas tran tpd TRIG ${in} VAL=${th} CROSS=1 TARG ${out} VAL=${th} CROSS=1")),

        new Preset("period", "Period & frequency",
                "Interval between two rising crossings of the same level",
                "tran",
                List.of(new Slot("sig", "Signal",         FieldKind.SIGNAL, "", ""),
                        new Slot("lvl", "Crossing level", FieldKind.TEXT, "", "e.g. mid-swing")),
                List.of("meas tran period TRIG ${sig} VAL=${lvl} RISE=1 TARG ${sig} VAL=${lvl} RISE=2",
                        "let freq = 1/period",
                        "print freq")),

        new Preset("overshoot", "Overshoot",
                "Peak relative to the settled value, in percent",
                "tran",
                List.of(new Slot("sig",  "Signal",       FieldKind.SIGNAL, "", ""),
                        new Slot("tset", "Settled time", FieldKind.TEXT, "", "a time after ringing dies")),
                List.of("meas tran vpeak MAX ${sig}",
                        "meas tran vfinal FIND ${sig} AT=${tset}",
                        "let overshoot_pct = 100*(vpeak - vfinal)/vfinal",
                        "print overshoot_pct")),

        new Preset("thd", "THD (fourier)",
                "Total harmonic distortion of a transient at the fundamental",
                "tran",
                List.of(new Slot("sig", "Signal",            FieldKind.SIGNAL, "", ""),
                        new Slot("f0",  "Fundamental freq",  FieldKind.TEXT, "", "e.g. 1k")),
                List.of("fourier ${f0} ${sig}")),

        new Preset("slope_at", "Slope at point",
                "dY/dX of a signal at a given time (slew rate). Stands in for the "
                        + "meas DERIV genre, which ngspice-46 doesn't implement.",
                "tran",
                List.of(new Slot("sig", "Signal",  FieldKind.SIGNAL, "", ""),
                        new Slot("at",  "At time", FieldKind.TEXT, "", "e.g. 1.3u")),
                List.of("plot dsig = deriv(${sig})",
                        "meas tran slope FIND dsig AT=${at}"))
    );

    /** Expands a preset's template with its slot values. */
    public static List<String> presetLines(Preset p, Map<String, String> slotVals) {
        return p.template().stream().map(line -> {
            String out = line;
            for (Slot s : p.slots()) {
                String v = slotVals.getOrDefault(s.key(), "");
                out = out.replace("${" + s.key() + "}", v == null ? "" : v.trim());
            }
            return out;
        }).toList();
    }

    /** First unfilled slot of a preset, or null when all are filled. */
    public static String validatePreset(Preset p, Map<String, String> slotVals) {
        for (Slot s : p.slots()) {
            String v = slotVals.get(s.key());
            if (v == null || v.isBlank()) return "Missing " + s.label() + ".";
        }
        return null;
    }

    // ------------------------------------------------------------------------
    // Result-name harvesting (for Test's `print` lines and the results view)
    // ------------------------------------------------------------------------

    /**
     * The result vectors a set of generated lines defines: the third token of
     * every {@code meas} line and the LHS of every {@code let}. Used by the
     * Test round-trip to {@code print} each one and grep the output.
     */
    public static List<String> resultNames(List<String> lines) {
        List<String> names = new java.util.ArrayList<>();
        for (String line : lines) {
            if (line == null) continue;
            String[] tok = line.strip().split("\\s+");
            String name = null;
            if (tok.length >= 3 && tok[0].equalsIgnoreCase("meas"))     name = tok[2];
            else if (tok.length >= 2 && tok[0].equalsIgnoreCase("let")) name = tok[1];
            if (name != null) {
                name = name.toLowerCase(Locale.ROOT);
                if (NAME_OK.matcher(name).matches() && !names.contains(name)) names.add(name);
            }
        }
        return names;
    }
}
