package com.circuitsim.simulation;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

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

        /** .AC results — outer key = frequency (Hz), inner key = "v(N)_mag" / "i(vmK)_mag" */
        public Map<Double, Map<String, Double>> acData   = new LinkedHashMap<>();

        /** .TRAN results — outer key = time (s), inner key = "v(N)" / "i(vmK)" */
        public Map<Double, Map<String, Double>> tranData = new LinkedHashMap<>();

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
            if ("0".equals(netName)) return "0.000000 V (Ground)";
            String key = "v(" + netName.toLowerCase() + ")";
            if (values.containsKey(key))
                return String.format("%.6f V", values.get(key));
            return "N/A";
        }

        public String getBranchCurrent(String sourceName) {
            String key = "i(" + sourceName.toLowerCase() + ")";
            if (values.containsKey(key))
                return String.format("%.6f A", values.get(key));
            return "N/A";
        }
    }

    public static Result run(String netlist) {
        return run(netlist, "hsa");
    }

    public static Result run(String netlist, String ngBehavior) {
        Result result = new Result();

        Path tempDir, netlistFile;
        try {
            tempDir     = Files.createTempDirectory("circuitsim");
            netlistFile = tempDir.resolve("circuit.cir");
            try (BufferedWriter w = Files.newBufferedWriter(netlistFile, StandardCharsets.UTF_8)) {
                w.write(netlist);
            }
            // Write .spiceinit with the selected ngbehavior compatibility mode when using a PDK
            // library. ngspice reads .spiceinit from the working directory at startup, before
            // parsing the netlist, so this is the only place the setting takes effect.
            if (netlist.contains(".lib ")) {
                String mode = (ngBehavior != null && !ngBehavior.isBlank()) ? ngBehavior : "hsa";
                Path spiceInit = tempDir.resolve(".spiceinit");
                try (BufferedWriter w = Files.newBufferedWriter(spiceInit, StandardCharsets.UTF_8)) {
                    w.write("set ngbehavior=" + mode + "\n");
                }
            }
        } catch (IOException e) {
            result.error = "Failed to create temporary netlist file: " + e.getMessage();
            return result;
        }

        String executable = resolveExecutable();
        if (executable == null) {
            result.error = "ngspice was not found. Please install ngspice and ensure " +
                    "ngspice_con.exe (or ngspice) is on your PATH.";
            return result;
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
            return result;
        }

        String fullOutput;
        try {
            fullOutput = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            result.error = "Failed to read ngspice output: " + e.getMessage();
            return result;
        }
        result.rawStdout = fullOutput;

        try {
            boolean finished = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                result.error = "ngspice timed out.";
                return result;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            result.error = "ngspice was interrupted.";
            return result;
        }

        try { Files.deleteIfExists(netlistFile); Files.deleteIfExists(tempDir); }
        catch (IOException ignored) {}

        String lower = fullOutput.toLowerCase();
        if (lower.contains("fatal") || lower.contains("no dc path")
                || lower.contains("singular matrix")) {
            result.error = "ngspice error:\n" + fullOutput.trim();
            return result;
        }

        String netlistLower = netlist.toLowerCase();
        boolean isAc   = netlistLower.contains(".ac ");
        boolean isTran = netlistLower.contains(".tran ");

        if (isTran) {
            parseTranOutput(fullOutput, result);
        } else if (isAc) {
            parseAcOutput(fullOutput, result);
        } else {
            parseOpOutput(fullOutput, result);
        }

        if (!isAc && !isTran && result.values.isEmpty()) {
            result.output.add("Simulation completed (no values parsed).");
            result.output.add("--- Raw ngspice output ---");
            for (String line : fullOutput.split("\n")) {
                if (!line.trim().isEmpty()) result.output.add("  " + line.trim());
            }
            return result;
        }

        if (!isAc && !isTran) {
            for (Map.Entry<String, Double> e : result.values.entrySet()) {
                String k = e.getKey();
                double v = e.getValue();
                if (k.startsWith("v("))      result.output.add(String.format("  %s = %.6f V", k, v));
                else if (k.startsWith("i(")) result.output.add(String.format("  %s = %.6f A", k, v));
                else                          result.output.add(String.format("  %s = %g",    k, v));
            }
            // result.extras is intentionally NOT appended here — SimulatePacket
            // emits it in its own colour so user print outputs stand out from
            // the default green node/branch values.
        }

        return result;
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
        double abs = Math.abs(v);
        if (abs == 0) return "0";
        if (abs >= 1e4 || abs < 1e-3) return String.format("%.6g", v);
        return String.format("%.6f", v);
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
    // Shared helpers
    // -------------------------------------------------------------------------

    private static String normaliseComplexRow(String row) {
        return row
                .replaceAll("\\(\\s*", "")
                .replaceAll("\\s*\\)", "")
                .replaceAll("\\s*,\\s*", ",");
    }

    private static double parseComplexMag(String token) {
        token = token.trim();
        int comma = token.lastIndexOf(',');
        if (comma >= 0) {
            try {
                double re = Double.parseDouble(token.substring(0, comma));
                double im = Double.parseDouble(token.substring(comma + 1));
                return Math.sqrt(re * re + im * im);
            } catch (NumberFormatException e) { return Double.NaN; }
        }
        try { return Math.abs(Double.parseDouble(token)); }
        catch (NumberFormatException e) { return Double.NaN; }
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