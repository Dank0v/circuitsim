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
        public List<String>  output  = new ArrayList<>();
        public String        error   = null;

        /** .OP results — keys like "v(1)", "i(vm1)" */
        public Map<String, Double> values = new LinkedHashMap<>();

        /**
         * .AC results — outer key is frequency (Hz), inner map is quantity key → value.
         * Quantity keys follow the pattern produced by buildAcNetlist:
         *   "v(N)_mag", "v(N)_db", "v(N)_phase"
         *   "i(vmK)_mag", "i(vmK)_phase"
         */
        public Map<Double, Map<String, Double>> acData = new LinkedHashMap<>();

        public String getNodeVoltage(int nodeIndex) {
            if (nodeIndex == 0) return "0.000000 V (Ground)";
            String key = "v(" + nodeIndex + ")";
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

    // -------------------------------------------------------------------------
    // Main entry point
    // -------------------------------------------------------------------------

    public static Result run(String netlist) {
        Result result = new Result();

        Path tempDir, netlistFile;
        try {
            tempDir     = Files.createTempDirectory("circuitsim");
            netlistFile = tempDir.resolve("circuit.cir");
            try (BufferedWriter w = Files.newBufferedWriter(netlistFile, StandardCharsets.UTF_8)) {
                w.write(netlist);
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

        boolean isAc = netlist.toLowerCase().contains(".ac ");
        if (isAc) {
            parseAcOutput(fullOutput, result);
        } else {
            parseOpOutput(fullOutput, result);
        }

        if (!isAc && result.values.isEmpty()) {
            result.output.add("Simulation completed (no values parsed).");
            result.output.add("--- Raw ngspice output ---");
            for (String line : fullOutput.split("\n")) {
                if (!line.trim().isEmpty()) result.output.add("  " + line.trim());
            }
            return result;
        }

        if (!isAc) {
            for (Map.Entry<String, Double> e : result.values.entrySet()) {
                String k = e.getKey();
                double v = e.getValue();
                if (k.startsWith("v("))      result.output.add(String.format("  %s = %.6f V", k, v));
                else if (k.startsWith("i(")) result.output.add(String.format("  %s = %.6f A", k, v));
                else                          result.output.add(String.format("  %s = %g",    k, v));
            }
        }

        return result;
    }

    // -------------------------------------------------------------------------
    // .OP output parser  (unchanged logic)
    // -------------------------------------------------------------------------

    private static void parseOpOutput(String output, Result result) {
        for (String rawLine : output.split("\n")) {
            String line = rawLine.trim();
            if (!line.contains("=")) continue;
            String[] parts = line.split("=", 2);
            if (parts.length != 2) continue;
            String key    = parts[0].trim().toLowerCase();
            String valStr = parts[1].trim();
            if (!key.startsWith("v(") && !key.startsWith("i(")) continue;
            try { result.values.put(key, Double.parseDouble(valStr)); }
            catch (NumberFormatException ignored) {}
        }
    }

    // -------------------------------------------------------------------------
    // .AC output parser
    // -------------------------------------------------------------------------

    /**
     * Parses .AC output from ngspice batch mode.
     *
     * ngspice prints complex values with spaces: ( 4.9997e+00 , 2.86e-04 )
     * We normalise each row first to collapse those into "4.9997e+00,2.86e-04",
     * then split on whitespace to get clean column tokens.
     *
     * Typical table layout:
     *   Index   frequency       v(1)                    v(2)
     *   -------------------------------------------------------
     *   0       1.000000e+01    ( 4.9997e+00, 2.86e-04) ...
     */
    private static void parseAcOutput(String output, Result result) {
        String[] lines = output.split("\n");

        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (!trimmed.toLowerCase().startsWith("index")) continue;

            String[] headerToks = trimmed.split("\\s+");
            if (headerToks.length < 3) continue;

            // Skip optional separator line
            int dataStart = i + 1;
            if (dataStart < lines.length && lines[dataStart].trim().startsWith("-")) {
                dataStart++;
            }

            for (int j = dataStart; j < lines.length; j++) {
                String raw = lines[j].trim();
                if (raw.isEmpty() || raw.startsWith("-") || raw.startsWith("=")) break;

                // Normalise complex notation: "( re , im )" -> "re,im"
                String row = normaliseComplexRow(raw);

                String[] tok = row.split("\\s+");
                if (tok.length < 3) continue;

                try { Integer.parseInt(tok[0]); } catch (NumberFormatException e) { break; }

                double freq;
                try { freq = Double.parseDouble(tok[1]); }
                catch (NumberFormatException e) { continue; }

                Map<String, Double> rowMap =
                        result.acData.computeIfAbsent(freq, k -> new LinkedHashMap<>());

                for (int col = 2; col < tok.length && col < headerToks.length; col++) {
                    String hdr = headerToks[col].toLowerCase();   // e.g. "v(1)", "i(vm1)"
                    double mag = parseComplexMag(tok[col]);
                    if (Double.isNaN(mag)) continue;
                    if (hdr.startsWith("v(") || hdr.startsWith("i(")) {
                        rowMap.put(hdr + "_mag", mag);
                    }
                }
            }
        }

        // ── Fallback: "key = ( re , im )" or "key = value" lines ─────────────
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

        if (!result.acData.isEmpty()) {
            result.output.add("AC analysis: " + result.acData.size() + " frequency points");
        } else {
            result.output.add("AC analysis complete (no data parsed). First 25 lines of ngspice output:");
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
     * Collapses ngspice's spaced complex notation into compact tokens.
     * "( 4.9997e+00 , 2.86e-04 )" -> "4.9997e+00,2.86e-04"
     * Also handles already-compact "4.9997e+00,2.86e-04" unchanged.
     */
    private static String normaliseComplexRow(String row) {
        // Remove opening paren and whitespace after it, closing paren, spaces around comma
        return row
                .replaceAll("\\(\\s*", "")   // "( " -> ""
                .replaceAll("\\s*\\)", "")    // " )" -> ""
                .replaceAll("\\s*,\\s*", ","); // " , " -> ","
    }

    /**
     * Parses a (possibly complex) value token and returns its magnitude.
     * "4.9997e+00,2.86e-04" -> sqrt(re^2 + im^2)
     * "4.9997e+00"          -> abs(value)
     * Returns NaN on parse failure.
     */
    private static double parseComplexMag(String token) {
        token = token.trim();
        int comma = token.lastIndexOf(',');
        if (comma >= 0) {
            try {
                double re = Double.parseDouble(token.substring(0, comma));
                double im = Double.parseDouble(token.substring(comma + 1));
                return Math.sqrt(re * re + im * im);
            } catch (NumberFormatException e) {
                return Double.NaN;
            }
        }
        try { return Math.abs(Double.parseDouble(token)); }
        catch (NumberFormatException e) { return Double.NaN; }
    }

    /**
     * Maps an ngspice AC print column header to our internal key convention.
     *
     * <p>ngspice column/key conventions:</p>
     * <ul>
     *   <li>{@code vm(N)}    → {@code v(N)_mag}</li>
     *   <li>{@code vdb(N)}   → {@code v(N)_db}</li>
     *   <li>{@code vp(N)}    → {@code v(N)_phase}</li>
     *   <li>{@code im(vmK)}  → {@code i(vmK)_mag}</li>
     *   <li>{@code ip(vmK)}  → {@code i(vmK)_phase}</li>
     * </ul>
     * Returns {@code null} for unrecognised headers.
     */
    private static String mapAcHeader(String hdr) {
        hdr = hdr.toLowerCase();
        if (hdr.startsWith("vm(") && hdr.endsWith(")")) {
            return "v(" + hdr.substring(3, hdr.length() - 1) + ")_mag";
        }
        if (hdr.startsWith("vdb(") && hdr.endsWith(")")) {
            return "v(" + hdr.substring(4, hdr.length() - 1) + ")_db";
        }
        if (hdr.startsWith("vp(") && hdr.endsWith(")")) {
            return "v(" + hdr.substring(3, hdr.length() - 1) + ")_phase";
        }
        if (hdr.startsWith("im(") && hdr.endsWith(")")) {
            return "i(" + hdr.substring(3, hdr.length() - 1) + ")_mag";
        }
        if (hdr.startsWith("ip(") && hdr.endsWith(")")) {
            return "i(" + hdr.substring(3, hdr.length() - 1) + ")_phase";
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Executable resolution
    // -------------------------------------------------------------------------

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