package com.circuitsim.simulation;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NgSpiceRunner {

    private static final String[] CANDIDATES = { "ngspice_con", "ngspice" };

    public static class Result {
        public List<String>  output   = new ArrayList<>();
        public String        error    = null;
        /** Full raw stdout from ngspice (with stderr merged), unparsed. */
        public String        rawStdout = "";

        /** .OP results — keys like "v(1)", "i(vm1)" */
        public Map<String, Double> values = new LinkedHashMap<>();

        /**
         * Pre-formatted output lines from user-supplied {@code print} commands
         * whose names aren't {@code v(...)}/{@code i(...)} — e.g.
         * {@code @m.xm1.msky130_fd_pr__nfet_01v8[gm] = 5.12e-04}. Each entry
         * is the line as it should be displayed to the user.
         */
        public List<String> extras = new ArrayList<>();

        /**
         * Programmatic lookup for OP-analysis extras. Keys are lowercased
         * names (matching {@code UserPlot.name}); values are the parsed
         * scalar. Mirrors {@link #extras} but is queryable.
         */
        public Map<String, Double> extrasByName = new LinkedHashMap<>();

        /**
         * Per-device operating points harvested from the {@code show <class>}
         * tables the .OP control block emits. Outer key = the ngspice device
         * name exactly as {@code show} prints it, lowercased — a flat name for
         * top-level devices ({@code "m1"}, {@code "r1"}) or a hierarchical path
         * for subcircuit-wrapped ones ({@code "m.xm1.m1"} for an sky130 IC
         * mosfet). Inner map = param name → value, in ngspice's listing order.
         * Populated for .OP runs only; empty otherwise.
         */
        public Map<String, LinkedHashMap<String, Double>> deviceOps = new LinkedHashMap<>();

        /** .AC results — outer key = frequency (Hz), inner key = "v(N)_mag" / "i(vmK)_mag" */
        public Map<Double, Map<String, Double>> acData   = new LinkedHashMap<>();

        /** .TRAN results — outer key = time (s), inner key = "v(N)" / "i(vmK)" */
        public Map<Double, Map<String, Double>> tranData = new LinkedHashMap<>();

        /**
         * .DC results — outer key = swept source value (V or A), inner key =
         * {@code v(N)} / {@code i(vmK)} / user-plot name. Single-sweep mode
         * only; the 2D dispatcher splits ngspice's chunked output into
         * separate Result objects (one per outer step) before this map is
         * populated.
         */
        public Map<Double, Map<String, Double>> dcData = new LinkedHashMap<>();

        /**
         * .NOISE results — outer key = frequency (Hz), inner key =
         * {@code onoise_spectrum} / {@code inoise_spectrum} (spectral
         * densities in V/√Hz or A/√Hz). The integrated totals
         * ({@code onoise_total} / {@code inoise_total}) land in
         * {@link #extrasByName}.
         */
        public Map<Double, Map<String, Double>> noiseData = new LinkedHashMap<>();

        /**
         * FFT spectra of a transient run's probed signals, produced by the
         * {@code linearize} + {@code fft} block the tran netlist appends.
         * Outer key = frequency (Hz), inner key = {@code v(N)_mag} /
         * {@code i(vmK)_mag} / user-plot name + {@code _mag} (magnitude of
         * the complex spectrum, same key scheme as {@link #acData}).
         */
        public Map<Double, Map<String, Double>> fftData = new LinkedHashMap<>();

        public String getNodeVoltage(int nodeIndex) {
            return getNodeVoltage(Integer.toString(nodeIndex));
        }

        /**
         * Looks up a node voltage by its netlist name — the integer node id as
         * a string ({@code "7"}) for unaliased nodes, or the sanitised probe
         * label ({@code "vout"}) when net aliasing is in effect.
         */
        public String getNodeVoltage(String netName) {
            if (netName == null) return "N/A";
            if ("0".equals(netName)) return "0V (Ground)";
            String key = "v(" + netName.toLowerCase() + ")";
            if (values.containsKey(key))
                return SiFormat.value(values.get(key)) + "V";
            return "N/A";
        }

        public String getBranchCurrent(String sourceName) {
            String key = "i(" + sourceName.toLowerCase() + ")";
            if (values.containsKey(key))
                return SiFormat.value(values.get(key)) + "A";
            return "N/A";
        }
    }

    /**
     * Marker echoed by the Param-block sweep loop before each {@code run};
     * {@link #runSweep} splits stdout on it to recover the per-step output.
     */
    public static final String SWEEP_MARKER = "PARAMSWEEP";

    /**
     * Result of a {@code .control}-loop parametric sweep: one parsed
     * {@link Result} per {@code run} iteration, in loop order.
     */
    public static class SweepResult {
        public String error = null;
        public String rawStdout = "";
        public final List<Result> steps = new ArrayList<>();
        /** Token printed after the marker, e.g. "1000" — one per step. */
        public final List<String> stepLabels = new ArrayList<>();
    }

    public static Result run(String netlist) {
        return run(netlist, "hsa");
    }

    /**
     * Runs a netlist whose control block loops {@code alterparam}/{@code
     * reset}/{@code run} over a swept {@code .param} (see SimulatePacket's
     * sweep wrapper), echoing {@link #SWEEP_MARKER} before each iteration.
     * The combined stdout is split at the markers and each segment is parsed
     * exactly like a standalone run of the same analysis type.
     */
    public static SweepResult runSweep(String netlist, String ngBehavior) {
        SweepResult sweep = new SweepResult();

        Result exec = new Result();
        String fullOutput = execute(netlist, ngBehavior, exec);
        if (exec.error != null) {
            sweep.error = exec.error;
            return sweep;
        }
        sweep.rawStdout = fullOutput;

        String lower = fullOutput.toLowerCase();
        if (lower.contains("fatal") || lower.contains("no dc path")
                || lower.contains("singular matrix")) {
            sweep.error = "ngspice error:\n" + fullOutput.trim();
            return sweep;
        }

        // Split into per-iteration segments. Text before the first marker is
        // load/expand chatter; text after the last marker also contains the
        // trailing batch-mode re-run, whose table repeats the final
        // iteration's values — merging it into that segment is harmless.
        String[] lines = fullOutput.split("\n");
        List<Integer> markerIdx = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().startsWith(SWEEP_MARKER)) markerIdx.add(i);
        }
        if (markerIdx.isEmpty()) {
            sweep.error = "Sweep produced no per-step output (marker not found).";
            return sweep;
        }
        for (int m = 0; m < markerIdx.size(); m++) {
            int from = markerIdx.get(m) + 1;
            int to   = m + 1 < markerIdx.size() ? markerIdx.get(m + 1) : lines.length;
            StringBuilder seg = new StringBuilder();
            for (int i = from; i < to; i++) seg.append(lines[i]).append('\n');

            String markerLine = lines[markerIdx.get(m)].trim();
            sweep.stepLabels.add(markerLine.substring(SWEEP_MARKER.length()).trim());

            Result step = new Result();
            step.rawStdout = seg.toString();
            parseByType(netlist, step.rawStdout, step);
            sweep.steps.add(step);
        }
        return sweep;
    }

    public static Result run(String netlist, String ngBehavior) {
        Result result = new Result();

        String fullOutput = execute(netlist, ngBehavior, result);
        if (result.error != null) return result;
        result.rawStdout = fullOutput;

        String lower = fullOutput.toLowerCase();
        if (lower.contains("fatal") || lower.contains("no dc path")
                || lower.contains("singular matrix")) {
            result.error = "ngspice error:\n" + fullOutput.trim();
            return result;
        }

        parseByType(netlist, fullOutput, result);

        String netlistLower = netlist.toLowerCase();
        boolean isNoise = netlistLower.contains(".noise ");
        boolean isAc    = netlistLower.contains(".ac ");
        boolean isTran  = netlistLower.contains(".tran ");
        boolean isDc    = netlistLower.contains(".dc ");
        boolean isOp = !isAc && !isTran && !isDc && !isNoise;

        if (isOp && result.values.isEmpty()) {
            result.output.add("Simulation completed (no values parsed).");
            result.output.add("--- Raw ngspice output ---");
            for (String line : fullOutput.split("\n")) {
                if (!line.trim().isEmpty()) result.output.add("  " + line.trim());
            }
            return result;
        }

        if (isOp) {
            for (Map.Entry<String, Double> e : result.values.entrySet()) {
                String k = e.getKey();
                double v = e.getValue();
                if (k.startsWith("v("))      result.output.add("  " + k + " = " + SiFormat.value(v) + "V");
                else if (k.startsWith("i(")) result.output.add("  " + k + " = " + SiFormat.value(v) + "A");
                else                          result.output.add("  " + k + " = " + SiFormat.value(v));
            }
            // result.extras is intentionally NOT appended here — SimulatePacket
            // emits it in its own colour so user print outputs stand out from
            // the default green node/branch values.
        }

        return result;
    }

    /**
     * Writes the netlist into a fresh temp dir (after PSpice-POLY include
     * preprocessing and optional .spiceinit emission), runs ngspice in batch
     * mode, and returns the merged stdout/stderr. On failure sets
     * {@code result.error} and returns the empty string.
     */
    private static String execute(String netlist, String ngBehavior, Result result) {
        Path tempDir, netlistFile;
        try {
            tempDir     = Files.createTempDirectory("circuitsim");
            netlistFile = tempDir.resolve("circuit.cir");
            // PSpice .lib files often use POLY E/F/G/H sources with comma+paren
            // controller syntax. ngspice's psa translator turns them into broken
            // xspice a-elements ("MIF-ERROR - unable to find definition of model
            // a$poly$e.*"). We sidestep that by reading each .INCLUDE'd file,
            // rewriting POLY syntax to ngspice-native (commas/parens stripped),
            // and redirecting the .INCLUDE to the normalized copy.
            netlist = preprocessIncludes(netlist, tempDir);
            try (BufferedWriter w = Files.newBufferedWriter(netlistFile, StandardCharsets.UTF_8)) {
                w.write(netlist);
            }
            // Write .spiceinit with the selected ngbehavior compatibility mode when the
            // netlist pulls in external libraries via .lib OR .INCLUDE. ngspice reads
            // .spiceinit from the working directory at startup, before parsing the
            // netlist, so this is the only place the setting takes effect. psa mode
            // emits .INCLUDE rather than .lib (PSpice has no hierarchical library
            // resolution), so we trigger on either token.
            String netlistUpper = netlist.toUpperCase();
            boolean hasLibDirective = netlistUpper.contains(".LIB ") || netlistUpper.contains(".INCLUDE");
            if (hasLibDirective) {
                String mode = (ngBehavior != null && !ngBehavior.isBlank()) ? ngBehavior : "hsa";
                Path spiceInit = tempDir.resolve(".spiceinit");
                try (BufferedWriter w = Files.newBufferedWriter(spiceInit, StandardCharsets.UTF_8)) {
                    w.write("set ngbehavior=" + mode + "\n");
                }
            }
        } catch (IOException e) {
            result.error = "Failed to create temporary netlist file: " + e.getMessage();
            return "";
        }

        String executable = resolveExecutable();
        if (executable == null) {
            result.error = "ngspice was not found. Please install ngspice and ensure " +
                    "ngspice_con.exe (or ngspice) is on your PATH.";
            return "";
        }

        Process process;
        try {
            ProcessBuilder pb = new ProcessBuilder(executable, "-b",
                    netlistFile.toAbsolutePath().toString());
            pb.redirectErrorStream(true);
            pb.directory(tempDir.toFile());
            process = pb.start();
        } catch (IOException e) {
            result.error = "Failed to start " + executable + ": " + e.getMessage();
            return "";
        }

        String fullOutput;
        try {
            fullOutput = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            result.error = "Failed to read ngspice output: " + e.getMessage();
            return "";
        }

        try {
            boolean finished = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                result.error = "ngspice timed out.";
                return "";
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            result.error = "ngspice was interrupted.";
            return "";
        }

        try { Files.deleteIfExists(netlistFile); Files.deleteIfExists(tempDir); }
        catch (IOException ignored) {}

        return fullOutput;
    }

    /** Routes {@code output} to the parser matching the netlist's analysis card. */
    private static void parseByType(String netlist, String output, Result result) {
        String netlistLower = netlist.toLowerCase();
        if (netlistLower.contains(".noise ")) {
            parseNoiseOutput(output, result);
        } else if (netlistLower.contains(".tran ")) {
            parseTranOutput(output, result);
            // The tran control block also linearize+fft's the probed signals
            // and prints them as frequency-scale chunks — pick those up too.
            parseTranFftOutput(output, result);
        } else if (netlistLower.contains(".ac ")) {
            parseAcOutput(output, result);
        } else if (netlistLower.contains(".dc ")) {
            parseDcOutput(output, result);
        } else {
            parseOpOutput(output, result);
            parseShowTables(output, result);
        }
    }

    // -------------------------------------------------------------------------
    // Transient FFT parser — "Index frequency v(...)" chunks emitted by the
    // linearize/fft/print block appended to every tran control section. The
    // spec plot's vectors are complex (re,im pairs), so this reuses the AC
    // parser's complex handling; values are stored as magnitudes under the
    // same "_mag"-suffixed keys the AC tables use.
    // -------------------------------------------------------------------------

    private static void parseTranFftOutput(String output, Result result) {
        String[] lines = output.split("\n");

        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (!trimmed.toLowerCase().startsWith("index")) continue;

            String[] headerToks = trimmed.split("\\s+");
            if (headerToks.length < 3) continue;
            // Only the FFT chunks carry a frequency scale in a tran output.
            if (!headerToks[1].equalsIgnoreCase("frequency")) continue;

            int dataStart = i + 1;
            if (dataStart < lines.length && lines[dataStart].trim().startsWith("-")) {
                dataStart++;
            }

            for (int j = dataStart; j < lines.length; j++) {
                String raw = lines[j].trim();
                if (raw.isEmpty() || raw.startsWith("-") || raw.startsWith("=")) break;

                String row = normaliseComplexRow(raw);
                String[] tok = row.split("\\s+");
                if (tok.length < 3) continue;

                try { Integer.parseInt(tok[0]); } catch (NumberFormatException e) { break; }

                double freq;
                try { freq = Double.parseDouble(tok[1]); }
                catch (NumberFormatException e) { continue; }

                Map<String, Double> rowMap =
                        result.fftData.computeIfAbsent(freq, k -> new LinkedHashMap<>());

                for (int col = 2; col < tok.length && col < headerToks.length; col++) {
                    String hdr = headerToks[col].toLowerCase();
                    double mag = parseComplexMag(tok[col]);
                    if (Double.isNaN(mag)) continue;
                    rowMap.put(hdr + "_mag", mag);
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // .OP parser
    // -------------------------------------------------------------------------

    private static void parseOpOutput(String output, Result result) {
        for (String rawLine : output.split("\n")) {
            String line = rawLine.trim();
            if (!line.contains("=")) continue;
            String[] parts = line.split("=", 2);
            if (parts.length != 2) continue;
            String keyOriginal = parts[0].trim();
            String keyLower    = keyOriginal.toLowerCase();
            String valStr      = parts[1].trim();
            // Need a parseable leading numeric token for both v/i and extras.
            String[] valToks = valStr.split("\\s+");
            if (valToks.length == 0) continue;
            double val;
            try { val = Double.parseDouble(valToks[0]); }
            catch (NumberFormatException ignored) { continue; }

            if (keyLower.startsWith("v(") || keyLower.startsWith("i(")) {
                result.values.put(keyLower, val);
            } else if (isUserPrintKey(keyOriginal)) {
                // Non-v/i scalar print outputs from user .control commands,
                // e.g. "@m.xm1.msky130_fd_pr__nfet_01v8_lvt[gm] = 5.12e-04".
                result.extras.add(keyOriginal + " = " + valStr);
                result.extrasByName.put(keyLower, val);
            }
        }
    }

    /**
     * True if {@code key} looks like the name a user would receive from a
     * {@code print} command. ngspice meta-output lines like
     * {@code "Doing analysis at TEMP"} or {@code "Total DRAM available"} have
     * whitespace in the key; real print names ({@code @m.xm1[gm]},
     * {@code i(vm1)}, custom {@code let}-defined identifiers) do not.
     */
    private static boolean isUserPrintKey(String keyOriginal) {
        if (keyOriginal == null || keyOriginal.isEmpty()) return false;
        for (int i = 0; i < keyOriginal.length(); i++) {
            if (Character.isWhitespace(keyOriginal.charAt(i))) return false;
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // `show <class>` device operating-point tables (.OP only)
    // -------------------------------------------------------------------------

    /**
     * Parses the column-major tables produced by {@code show <class>} commands
     * (one per device family) that the .OP control block appends. Each table
     * looks like:
     *
     * <pre>
     *  Resistor: Simple linear resistor
     *      device              r1            r2
     *       model               R             R
     *           i         0.000667       0.000333
     *           p         0.000444       0.000222
     * </pre>
     *
     * The {@code device} row names the columns (device instance names); every
     * subsequent row is {@code param val1 val2 …} until a blank line or the
     * next {@code device} header. Subcircuit-wrapped devices appear with their
     * full hierarchical name ({@code m.xm1.m1}). Wide tables are wrapped by
     * ngspice into several column groups, each re-emitting its own
     * {@code device}/{@code model} header — keying by device name across groups
     * stitches them back together. Non-numeric cells (e.g. {@code "-"} for an
     * unset source spec) are skipped.
     */
    private static void parseShowTables(String output, Result result) {
        String[] lines = output.split("\n");
        List<String> cols = null;          // device names of the current column group
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) { cols = null; continue; }
            String[] tok = line.split("\\s+");
            if (tok[0].equals("device")) {
                cols = new ArrayList<>(tok.length - 1);
                for (int c = 1; c < tok.length; c++) {
                    String name = tok[c].toLowerCase();
                    cols.add(name);
                    result.deviceOps.computeIfAbsent(name, k -> new LinkedHashMap<>());
                }
                continue;
            }
            if (cols == null) continue;     // banner / chatter before any header
            if (tok[0].equals("model")) continue;   // model-name row is not numeric
            String param = tok[0].toLowerCase();
            for (int c = 0; c < cols.size() && c + 1 < tok.length; c++) {
                try {
                    double v = Double.parseDouble(tok[c + 1]);
                    result.deviceOps.get(cols.get(c)).put(param, v);
                } catch (NumberFormatException ignored) {
                    // "-" or other non-numeric placeholder for this cell — skip.
                }
            }
        }
        injectDerivedMosParams(result);
    }

    /**
     * Appends analog-design figures of merit to every mosfet's operating point,
     * computed from the raw {@code show m} params:
     *
     * <ul>
     *   <li>{@code gm/id}  — transconductance efficiency (1/V)</li>
     *   <li>{@code gm/gds} — intrinsic gain</li>
     *   <li>{@code ft}     — transit frequency, gm / (2π·cgg) (Hz)</li>
     * </ul>
     *
     * Mosfets are recognised by their show-class letter — the device name (flat
     * {@code m1} or hierarchical {@code m.xm1.…}) always starts with {@code m},
     * which no other family uses. BSIM models report {@code cgg} directly;
     * Meyer-cap models (MOS levels 1–3) don't, so the gate capacitance falls
     * back to {@code cgs + cgd + cgb}. Values are stored as magnitudes (PMOS
     * {@code id} is negative), inserted right after {@code gm} so they sit next
     * to their source params in the picker grid rather than behind ~85 raw
     * BSIM4 params. A ratio whose denominator is 0 or missing (e.g. an
     * uncharged Meyer cap) is simply not emitted.
     */
    private static void injectDerivedMosParams(Result result) {
        for (Map.Entry<String, LinkedHashMap<String, Double>> dev : result.deviceOps.entrySet()) {
            if (!dev.getKey().startsWith("m")) continue;
            LinkedHashMap<String, Double> p = dev.getValue();
            Double gm = p.get("gm");
            if (gm == null) continue;

            LinkedHashMap<String, Double> derived = new LinkedHashMap<>();
            putRatio(derived, "gm/id",  gm, p.get("id"));
            putRatio(derived, "gm/gds", gm, p.get("gds"));

            Double cgg = p.get("cgg");
            if (cgg == null && (p.containsKey("cgs") || p.containsKey("cgd") || p.containsKey("cgb"))) {
                cgg = p.getOrDefault("cgs", 0.0) + p.getOrDefault("cgd", 0.0)
                        + p.getOrDefault("cgb", 0.0);
            }
            putRatio(derived, "ft", gm / (2 * Math.PI), cgg);
            if (derived.isEmpty()) continue;

            LinkedHashMap<String, Double> rebuilt = new LinkedHashMap<>(p.size() + derived.size());
            for (Map.Entry<String, Double> e : p.entrySet()) {
                rebuilt.put(e.getKey(), e.getValue());
                if (e.getKey().equals("gm")) rebuilt.putAll(derived);
            }
            dev.setValue(rebuilt);
        }
    }

    /** Stores {@code |num/den|} under {@code name}, if the ratio is computable. */
    private static void putRatio(Map<String, Double> out, String name, double num, Double den) {
        if (den == null || den == 0) return;
        double v = Math.abs(num / den);
        if (Double.isFinite(v)) out.put(name, v);
    }

    // -------------------------------------------------------------------------
    // .AC parser  — handles multiple ngspice output chunks
    // -------------------------------------------------------------------------

    private static void parseAcOutput(String output, Result result) {
        String[] lines = output.split("\n");

        // ngspice splits large tables into chunks, each starting with an "index" header.
        // We must iterate over ALL chunks, not just the first one.
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (!trimmed.toLowerCase().startsWith("index")) continue;

            String[] headerToks = trimmed.split("\\s+");
            if (headerToks.length < 3) continue;

            // Skip the optional dashed separator line
            int dataStart = i + 1;
            if (dataStart < lines.length && lines[dataStart].trim().startsWith("-")) {
                dataStart++;
            }

            for (int j = dataStart; j < lines.length; j++) {
                String raw = lines[j].trim();
                // An empty line or separator marks the end of this chunk (not the whole output)
                if (raw.isEmpty() || raw.startsWith("-") || raw.startsWith("=")) break;

                String row = normaliseComplexRow(raw);
                String[] tok = row.split("\\s+");
                if (tok.length < 3) continue;

                // Stop if the first token is not an integer row index
                try { Integer.parseInt(tok[0]); } catch (NumberFormatException e) { break; }

                double freq;
                try { freq = Double.parseDouble(tok[1]); }
                catch (NumberFormatException e) { continue; }

                Map<String, Double> rowMap =
                        result.acData.computeIfAbsent(freq, k -> new LinkedHashMap<>());

                for (int col = 2; col < tok.length && col < headerToks.length; col++) {
                    String hdr = headerToks[col].toLowerCase();
                    double mag = parseComplexMag(tok[col]);
                    if (Double.isNaN(mag)) continue;
                    // Capture v/i probe columns AND user-plot columns (any other
                    // header named by a `let`/`plot` directive). The store key
                    // always carries the "_mag" suffix so callers can look up
                    // either by probe name or plot name uniformly.
                    rowMap.put(hdr + "_mag", mag);
                }
            }
            // Do NOT break here — continue scanning for the next chunk header
        }

        // Fallback: "v(1) = value" lines (single-point AC)
        if (result.acData.isEmpty()) {
            for (String rawLine : lines) {
                String line = rawLine.trim();
                if (!line.contains("=")) continue;
                String[] parts = line.split("=", 2);
                if (parts.length != 2) continue;
                String key = parts[0].trim().toLowerCase();
                if (!key.startsWith("v(") && !key.startsWith("i(")) continue;
                String valStr = normaliseComplexRow(parts[1].trim());
                double mag = parseComplexMag(valStr.split("\\s+")[0]);
                if (!Double.isNaN(mag)) {
                    result.acData.computeIfAbsent(0.0, k -> new LinkedHashMap<>())
                            .put(key + "_mag", mag);
                }
            }
        }

        // Pick up scalar `meas` / `print` outputs (e.g. dc_gain, gbw, pm),
        // which ngspice prints as plain "name = value" lines outside of any
        // tabular block. These flow into result.extras for display, and into
        // result.extrasByName for programmatic lookup.
        parseScalarMeasurements(lines, result);

        if (!result.acData.isEmpty()) {
            result.output.add("AC analysis: " + result.acData.size() + " frequency points");
        } else {
            result.output.add("AC analysis complete (no data parsed). First 25 lines:");
            int shown = 0;
            for (String l : lines) {
                if (!l.trim().isEmpty()) {
                    result.output.add("  " + l.trim());
                    if (++shown >= 25) break;
                }
            }
        }
    }

    /**
     * Scans for {@code name = value} scalar lines outside of tabular blocks,
     * the form ngspice uses for {@code .meas} / {@code print} of a single
     * value. Skips {@code v(...)} / {@code i(...)} (they belong to the per-
     * frequency table) and skips lines that look like meta-output (any
     * whitespace inside the key).
     */
    private static void parseScalarMeasurements(String[] lines, Result result) {
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (!line.contains("=")) continue;
            String[] parts = line.split("=", 2);
            if (parts.length != 2) continue;
            String keyOriginal = parts[0].trim();
            String keyLower    = keyOriginal.toLowerCase();
            String valStr      = parts[1].trim();
            if (keyLower.startsWith("v(") || keyLower.startsWith("i(")) continue;
            if (!isUserPrintKey(keyOriginal)) continue;
            // First token of the value side must be a parseable number.
            String[] valToks = valStr.split("\\s+");
            if (valToks.length == 0) continue;
            double val;
            try { val = Double.parseDouble(valToks[0]); }
            catch (NumberFormatException ignored) { continue; }
            // Deduplicate by name — keep only the latest value.
            result.extrasByName.put(keyLower, val);
            // result.extras is built as a deduped, ordered display list at end.
        }
        // Rebuild result.extras from extrasByName preserving insertion order.
        if (!result.extrasByName.isEmpty()) {
            result.extras.clear();
            for (Map.Entry<String, Double> e : result.extrasByName.entrySet()) {
                result.extras.add(e.getKey() + " = " + formatScalar(e.getValue()));
            }
        }
    }

    private static String formatScalar(double v) {
        return SiFormat.value(v);
    }

    // -------------------------------------------------------------------------
    // .TRAN parser  — handles multiple ngspice output chunks
    // -------------------------------------------------------------------------

    private static void parseTranOutput(String output, Result result) {
        String[] lines = output.split("\n");

        // ngspice splits large transient tables into chunks (typically ~50 rows each),
        // every chunk beginning with its own "index  time  v(N)..." header row.
        // We must scan ALL chunks, not just stop after the first one.
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (!trimmed.toLowerCase().startsWith("index")) continue;

            String[] headerToks = trimmed.split("\\s+");
            if (headerToks.length < 3) continue;
            // Only handle tran tables (second column must be "time")
            if (!headerToks[1].equalsIgnoreCase("time")) continue;

            // Skip optional dashed separator
            int dataStart = i + 1;
            if (dataStart < lines.length && lines[dataStart].trim().startsWith("-")) {
                dataStart++;
            }

            for (int j = dataStart; j < lines.length; j++) {
                String raw = lines[j].trim();
                // Empty line or separator = end of this chunk, NOT end of all data
                if (raw.isEmpty() || raw.startsWith("-") || raw.startsWith("=")) break;

                String[] tok = raw.split("\\s+");
                if (tok.length < 3) continue;

                // Stop if the first token is not a row index integer
                try { Integer.parseInt(tok[0]); } catch (NumberFormatException e) { break; }

                double time;
                try { time = Double.parseDouble(tok[1]); }
                catch (NumberFormatException e) { continue; }

                Map<String, Double> rowMap =
                        result.tranData.computeIfAbsent(time, k -> new LinkedHashMap<>());

                for (int col = 2; col < tok.length && col < headerToks.length; col++) {
                    String hdr = headerToks[col].toLowerCase();
                    try {
                        double val = Double.parseDouble(tok[col]);
                        // Capture v/i probe columns AND any user-plot column
                        // emitted by a `let`/`plot` directive in the .control block.
                        rowMap.put(hdr, val);
                    } catch (NumberFormatException ignored) {}
                }
            }
            // Do NOT break here — continue scanning for the next chunk header
        }

        // Fallback: "v(1) = value" lines
        if (result.tranData.isEmpty()) {
            for (String rawLine : lines) {
                String line = rawLine.trim();
                if (!line.contains("=")) continue;
                String[] parts = line.split("=", 2);
                if (parts.length != 2) continue;
                String key = parts[0].trim().toLowerCase();
                if (!key.startsWith("v(") && !key.startsWith("i(")) continue;
                try {
                    double val = Double.parseDouble(parts[1].trim());
                    result.tranData.computeIfAbsent(0.0, k -> new LinkedHashMap<>()).put(key, val);
                } catch (NumberFormatException ignored) {}
            }
        }

        if (!result.tranData.isEmpty()) {
            result.output.add("Transient analysis: " + result.tranData.size() + " time points");
        } else {
            result.output.add("Transient analysis complete (no data parsed). First 25 lines:");
            int shown = 0;
            for (String l : lines) {
                if (!l.trim().isEmpty()) {
                    result.output.add("  " + l.trim());
                    if (++shown >= 25) break;
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // .NOISE parser — spectral-density table plus integrated totals
    // -------------------------------------------------------------------------

    private static void parseNoiseOutput(String output, Result result) {
        // A bad input-source name aborts the analysis with a warning rather
        // than a "fatal" line, so the generic error sniffing above misses it.
        for (String rawLine : output.split("\n")) {
            String line = rawLine.trim();
            if (line.startsWith("Warning: Noise input source")
                    || line.contains("Noise output source") && line.contains("not in circuit")) {
                result.error = line;
                return;
            }
        }

        String[] lines = output.split("\n");

        // Spectral-density chunks: "Index   frequency   onoise_spectrum ..."
        // — same tabular shape as AC output, all values real.
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (!trimmed.toLowerCase().startsWith("index")) continue;

            String[] headerToks = trimmed.split("\\s+");
            if (headerToks.length < 3) continue;
            if (!headerToks[1].equalsIgnoreCase("frequency")) continue;

            int dataStart = i + 1;
            if (dataStart < lines.length && lines[dataStart].trim().startsWith("-")) {
                dataStart++;
            }

            for (int j = dataStart; j < lines.length; j++) {
                String raw = lines[j].trim();
                if (raw.isEmpty() || raw.startsWith("-") || raw.startsWith("=")) break;

                String[] tok = raw.split("\\s+");
                if (tok.length < 3) continue;

                try { Integer.parseInt(tok[0]); } catch (NumberFormatException e) { break; }

                double freq;
                try { freq = Double.parseDouble(tok[1]); }
                catch (NumberFormatException e) { continue; }

                Map<String, Double> rowMap =
                        result.noiseData.computeIfAbsent(freq, k -> new LinkedHashMap<>());

                for (int col = 2; col < tok.length && col < headerToks.length; col++) {
                    String hdr = headerToks[col].toLowerCase();
                    try {
                        rowMap.put(hdr, Double.parseDouble(tok[col]));
                    } catch (NumberFormatException ignored) {}
                }
            }
        }

        // Integrated totals print as scalar "onoise_total = 1.21e-06" lines.
        parseScalarMeasurements(lines, result);

        if (!result.noiseData.isEmpty()) {
            result.output.add("Noise analysis: " + result.noiseData.size() + " frequency points");
        } else {
            result.output.add("Noise analysis complete (no data parsed). First 25 lines:");
            int shown = 0;
            for (String l : lines) {
                if (!l.trim().isEmpty()) {
                    result.output.add("  " + l.trim());
                    if (++shown >= 25) break;
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // .DC parser — tabular, similar to TRAN but X = swept source value
    // -------------------------------------------------------------------------

    private static void parseDcOutput(String output, Result result) {
        String[] lines = output.split("\n");

        // ngspice emits .dc results as one or more chunks, each starting with
        // an "index  <sweep-name>  v(...)" header. The sweep column header is
        // typically "v-sweep" or "i-sweep" but we accept anything that is not
        // "time" (TRAN) or "frequency" (AC) so DC parsing remains the natural
        // fallback for tabular non-time non-frequency output.
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (!trimmed.toLowerCase().startsWith("index")) continue;

            String[] headerToks = trimmed.split("\\s+");
            if (headerToks.length < 3) continue;
            String secondColLower = headerToks[1].toLowerCase();
            if (secondColLower.equals("time") || secondColLower.equals("frequency")) continue;

            // Skip optional dashed separator
            int dataStart = i + 1;
            if (dataStart < lines.length && lines[dataStart].trim().startsWith("-")) {
                dataStart++;
            }

            for (int j = dataStart; j < lines.length; j++) {
                String raw = lines[j].trim();
                if (raw.isEmpty() || raw.startsWith("-") || raw.startsWith("=")) break;

                String[] tok = raw.split("\\s+");
                if (tok.length < 3) continue;

                try { Integer.parseInt(tok[0]); } catch (NumberFormatException e) { break; }

                double sweepVal;
                try { sweepVal = Double.parseDouble(tok[1]); }
                catch (NumberFormatException e) { continue; }

                Map<String, Double> rowMap =
                        result.dcData.computeIfAbsent(sweepVal, k -> new LinkedHashMap<>());

                for (int col = 2; col < tok.length && col < headerToks.length; col++) {
                    String hdr = headerToks[col].toLowerCase();
                    try {
                        double val = Double.parseDouble(tok[col]);
                        rowMap.put(hdr, val);
                    } catch (NumberFormatException ignored) {}
                }
            }
            // Don't break — there may be more chunks (e.g. 2D sweep).
        }

        // Pick up scalar `meas` / `print` outputs (e.g. dc_gain, gbw, pm),
        // which ngspice prints as plain "name = value" lines outside of any
        // tabular block. Mirrors what the AC parser does.
        parseScalarMeasurements(lines, result);

        if (!result.dcData.isEmpty()) {
            result.output.add("DC sweep: " + result.dcData.size() + " sweep points");
        } else {
            result.output.add("DC sweep complete (no data parsed). First 25 lines:");
            int shown = 0;
            for (String l : lines) {
                if (!l.trim().isEmpty()) {
                    result.output.add("  " + l.trim());
                    if (++shown >= 25) break;
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Shared helpers
    // -------------------------------------------------------------------------

    private static String normaliseComplexRow(String row) {
        return row
                .replaceAll("\\(\\s*", "")
                .replaceAll("\\s*\\)", "")
                .replaceAll("\\s*,\\s*", ",");
    }

    /**
     * Parses one token from an AC results column. ngspice prints AC vectors as
     * complex pairs ({@code re,im}); for true complex voltages/currents we
     * want the magnitude, but real-valued vectors produced by user expressions
     * — {@code db()}, {@code phase()}, {@code re()}, {@code im()}, etc. — are
     * emitted with {@code im == 0} and need to keep their sign. Heuristic:
     * if the imaginary part is exactly zero, return the real part as-is;
     * otherwise return the modulus.
     */
    private static double parseComplexMag(String token) {
        token = token.trim();
        int comma = token.lastIndexOf(',');
        if (comma >= 0) {
            try {
                double re = Double.parseDouble(token.substring(0, comma));
                double im = Double.parseDouble(token.substring(comma + 1));
                if (im == 0.0) return re;
                return Math.sqrt(re * re + im * im);
            } catch (NumberFormatException e) { return Double.NaN; }
        }
        try { return Double.parseDouble(token); }
        catch (NumberFormatException e) { return Double.NaN; }
    }

    // -------------------------------------------------------------------------
    // PSpice .lib preprocessing
    // -------------------------------------------------------------------------

    /** Captures the file path inside a {@code .INCLUDE "..."} directive (case-insensitive). */
    private static final Pattern INCLUDE_PATTERN = Pattern.compile(
            "(?im)^\\s*\\.include\\s+\"([^\"]+)\"\\s*$");

    /**
     * Quick detector — true for any line that looks like an E/F/G/H source
     * with a {@code POLY(N)} controller list. Full parsing happens in
     * {@link #convertPolyLine}.
     */
    private static final Pattern POLY_LINE = Pattern.compile(
            "^\\s*[EeFfGgHh]\\S*\\s+\\S+\\s+\\S+\\s+[Pp][Oo][Ll][Yy]\\s*\\(",
            Pattern.DOTALL);

    /**
     * Walks the netlist, normalizes every {@code .INCLUDE}'d library to remove
     * PSpice POLY-syntax commas/parens, writes the normalized copies into
     * {@code tempDir}, and returns a new netlist whose {@code .INCLUDE} paths
     * point at the temp copies. If a referenced file can't be read, the
     * original {@code .INCLUDE} is left as-is so ngspice produces its usual
     * file-not-found error rather than a silent failure.
     */
    private static String preprocessIncludes(String netlist, Path tempDir) throws IOException {
        Matcher m = INCLUDE_PATTERN.matcher(netlist);
        StringBuilder out = new StringBuilder(netlist.length());
        int cursor = 0;
        int seq = 0;
        while (m.find()) {
            out.append(netlist, cursor, m.start());
            String origPath = m.group(1);
            Path src = Paths.get(origPath);
            if (Files.isRegularFile(src)) {
                Path dst = tempDir.resolve("lib_" + (seq++) + ".lib");
                normalizeLibFile(src, dst);
                out.append(".INCLUDE \"").append(dst.toAbsolutePath()).append('"');
            } else {
                out.append(m.group());
            }
            cursor = m.end();
        }
        out.append(netlist, cursor, netlist.length());
        return out.toString();
    }

    /**
     * Copies {@code src} → {@code dst}, applying {@link #normalizePspiceLine}
     * to each line.
     *
     * <p>The file is read at byte level and every byte {@code >= 0x80} is
     * replaced with a space before decoding. OrCAD's original
     * {@code OPAMP.LIB} (shipped with Cadence PSpice) contains stray
     * {@code 0x81} bytes left over from old authoring tools, which ngspice's
     * strict UTF-8 reader rejects outright. Since SPICE netlists are
     * ASCII-only this byte-level scrub never destroys real content — it just
     * removes the corrupted control bytes that would otherwise abort the
     * include. (Same fix as the user's separate {@code regen_opamp.py},
     * applied transparently each simulation.)
     */
    private static void normalizeLibFile(Path src, Path dst) throws IOException {
        byte[] data = Files.readAllBytes(src);
        for (int i = 0; i < data.length; i++) {
            if (data[i] < 0) data[i] = 0x20; // signed-byte < 0 == unsigned byte >= 0x80
        }
        String text = new String(data, StandardCharsets.US_ASCII);
        try (BufferedReader r = new BufferedReader(new java.io.StringReader(text));
             BufferedWriter w = Files.newBufferedWriter(dst, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                w.write(normalizePspiceLine(line));
                w.newLine();
            }
        }
    }

    /**
     * Rewrites a PSpice POLY E/F/G/H source into a ngspice B-source. We have to
     * do this — and not just normalize syntax — because ngspice's {@code psa}
     * compatibility mode unconditionally rewrites every POLY source into an
     * XSPICE {@code a_poly} element whose model definition is never emitted,
     * producing the {@code MIF-ERROR - unable to find definition of model
     * a$poly$e.*} that kills the run. B-sources are not touched by psa.
     *
     * <p>The polynomial is expanded following PSpice's coefficient ordering
     * (constant, linear, quadratic with X_i*X_j for i<=j, cubic, ...). Both
     * linear and higher-order forms are supported; trailing coefficients that
     * are zero in the source line can be omitted (PSpice convention).
     *
     * <p>Source-type mapping:
     * <ul>
     *   <li>{@code E} VCVS → {@code B... V = expr(V-diffs)}
     *   <li>{@code G} VCCS → {@code B... I = expr(V-diffs)}
     *   <li>{@code F} CCCS → {@code B... I = expr(I(Vname))}
     *   <li>{@code H} CCVS → {@code B... V = expr(I(Vname))}
     * </ul>
     * The B-source name is the original device name prefixed with {@code B}.
     * If parsing fails the input line is returned unchanged so the failure
     * surfaces as a normal ngspice error rather than silent corruption.
     */
    static String normalizePspiceLine(String line) {
        if (!POLY_LINE.matcher(line).find()) return line;

        String stripped = line.replace(',', ' ').replace('(', ' ').replace(')', ' ');
        stripped = stripped.replaceAll("\\s+", " ").trim();
        String[] t = stripped.split(" ");
        if (t.length < 6) return line;
        if (!t[3].equalsIgnoreCase("POLY")) return line;

        char srcType = Character.toUpperCase(t[0].charAt(0));
        boolean voltageControlled = (srcType == 'E' || srcType == 'G');
        boolean currentControlled = (srcType == 'F' || srcType == 'H');
        if (!voltageControlled && !currentControlled) return line;

        int n;
        try { n = Integer.parseInt(t[4]); }
        catch (NumberFormatException ignore) { return line; }
        if (n < 1) return line;

        int ctrlTokens = voltageControlled ? 2 * n : n;
        int coeffStart = 5 + ctrlTokens;
        int coeffCount = t.length - coeffStart;
        if (coeffCount < 1) return line;

        String[] controllerExpr = new String[n];
        if (voltageControlled) {
            for (int i = 0; i < n; i++) {
                controllerExpr[i] = "(V(" + t[5 + 2 * i] + ")-V(" + t[5 + 2 * i + 1] + "))";
            }
        } else {
            for (int i = 0; i < n; i++) {
                controllerExpr[i] = "I(" + t[5 + i] + ")";
            }
        }

        double[] p = new double[coeffCount];
        for (int i = 0; i < coeffCount; i++) {
            Double parsed = parseSpiceNumber(t[coeffStart + i]);
            if (parsed == null) return line;
            p[i] = parsed;
        }

        // PSpice term ordering: constant, then for each order ≥ 1 all
        // multi-indices (i1,...,im) with 1 <= i1 <= ... <= im <= n, lex order.
        List<int[]> terms = new ArrayList<>();
        terms.add(new int[0]);
        int order = 1;
        while (terms.size() < coeffCount && order <= 10) {
            generatePolyTerms(terms, n, order, new int[order], 0, 1);
            order++;
        }
        if (terms.size() < coeffCount) return line;

        StringBuilder expr = new StringBuilder();
        boolean first = true;
        for (int i = 0; i < coeffCount; i++) {
            if (p[i] == 0) continue;
            int[] term = terms.get(i);
            if (first) {
                if (p[i] < 0) expr.append("-");
                first = false;
            } else {
                expr.append(p[i] > 0 ? " + " : " - ");
            }
            double absC = Math.abs(p[i]);
            boolean omitOne = (absC == 1.0 && term.length > 0);
            if (!omitOne) expr.append(absC);
            for (int j = 0; j < term.length; j++) {
                if (j > 0 || !omitOne) expr.append('*');
                expr.append(controllerExpr[term[j] - 1]);
            }
        }
        if (first) expr.append('0');

        String outChannel = (srcType == 'E' || srcType == 'H') ? "V" : "I";
        return "B" + t[0] + " " + t[1] + " " + t[2] + " " + outChannel + " = " + expr;
    }

    /**
     * Parses a SPICE-style number — base value plus an optional SI suffix
     * (case-insensitive): {@code T G MEG K M U N P F}, with the SPICE
     * convention that {@code M = milli} (not mega — that is {@code MEG}).
     * A trailing unit ({@code V/A/F/H/Ohm/...}) is ignored if present.
     * Returns {@code null} if the token cannot be parsed.
     */
    static Double parseSpiceNumber(String s) {
        if (s == null || s.isEmpty()) return null;
        int i = 0, len = s.length();
        if (i < len && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
        boolean sawDigit = false;
        while (i < len && Character.isDigit(s.charAt(i))) { i++; sawDigit = true; }
        if (i < len && s.charAt(i) == '.') {
            i++;
            while (i < len && Character.isDigit(s.charAt(i))) { i++; sawDigit = true; }
        }
        if (!sawDigit) return null;
        if (i < len && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
            int j = i + 1;
            if (j < len && (s.charAt(j) == '+' || s.charAt(j) == '-')) j++;
            int kStart = j;
            while (j < len && Character.isDigit(s.charAt(j))) j++;
            if (j > kStart) i = j; // otherwise treat the 'e' as start of an SI suffix
        }
        double base;
        try { base = Double.parseDouble(s.substring(0, i)); }
        catch (NumberFormatException e) { return null; }
        String suffix = s.substring(i).toLowerCase();
        double scale = 1.0;
        if (suffix.startsWith("meg")) scale = 1e6;
        else if (suffix.startsWith("mil")) scale = 25.4e-6;
        else if (!suffix.isEmpty()) {
            switch (suffix.charAt(0)) {
                case 't' -> scale = 1e12;
                case 'g' -> scale = 1e9;
                case 'k' -> scale = 1e3;
                case 'm' -> scale = 1e-3;
                case 'u' -> scale = 1e-6;
                case 'n' -> scale = 1e-9;
                case 'p' -> scale = 1e-12;
                case 'f' -> scale = 1e-15;
                default  -> scale = 1.0; // unit suffix (V, A, Ohm, …) — ignore
            }
        }
        return base * scale;
    }

    /**
     * Appends every multi-index of {@code order} from {@code [minVar..n]} to
     * {@code out}, in PSpice's lexicographic non-decreasing order. Called once
     * per polynomial order; the caller seeds the constant term separately.
     */
    private static void generatePolyTerms(List<int[]> out, int n, int order,
                                           int[] buf, int depth, int minVar) {
        if (depth == order) {
            out.add(buf.clone());
            return;
        }
        for (int v = minVar; v <= n; v++) {
            buf[depth] = v;
            generatePolyTerms(out, n, order, buf, depth + 1, v);
        }
    }

    private static String resolveExecutable() {
        for (String candidate : CANDIDATES) {
            try {
                Process p = new ProcessBuilder(candidate, "-v")
                        .redirectErrorStream(true).start();
                p.getInputStream().readAllBytes();
                boolean done = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                if (!done) { p.destroyForcibly(); continue; }
                return candidate;
            } catch (IOException | InterruptedException ignored) {}
        }
        return null;
    }
}