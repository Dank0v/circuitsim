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

        /** .OP results — keys like "v(1)", "i(vm1)" */
        public Map<String, Double> values = new LinkedHashMap<>();

        /** .AC results — outer key = frequency (Hz), inner key = "v(N)_mag" / "i(vmK)_mag" */
        public Map<Double, Map<String, Double>> acData   = new LinkedHashMap<>();

        /** .TRAN results — outer key = time (s), inner key = "v(N)" / "i(vmK)" */
        public Map<Double, Map<String, Double>> tranData = new LinkedHashMap<>();

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
            String key    = parts[0].trim().toLowerCase();
            String valStr = parts[1].trim();
            if (!key.startsWith("v(") && !key.startsWith("i(")) continue;
            try { result.values.put(key, Double.parseDouble(valStr)); }
            catch (NumberFormatException ignored) {}
        }
    }

    // -------------------------------------------------------------------------
    // .AC parser
    // -------------------------------------------------------------------------

    private static void parseAcOutput(String output, Result result) {
        String[] lines = output.split("\n");

        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (!trimmed.toLowerCase().startsWith("index")) continue;

            String[] headerToks = trimmed.split("\\s+");
            if (headerToks.length < 3) continue;

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
                        result.acData.computeIfAbsent(freq, k -> new LinkedHashMap<>());

                for (int col = 2; col < tok.length && col < headerToks.length; col++) {
                    String hdr = headerToks[col].toLowerCase();
                    double mag = parseComplexMag(tok[col]);
                    if (Double.isNaN(mag)) continue;
                    if (hdr.startsWith("v(") || hdr.startsWith("i(")) {
                        rowMap.put(hdr + "_mag", mag);
                    }
                }
            }
        }

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

    // -------------------------------------------------------------------------
    // .TRAN parser
    // -------------------------------------------------------------------------

    private static void parseTranOutput(String output, Result result) {
        String[] lines = output.split("\n");

        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (!trimmed.toLowerCase().startsWith("index")) continue;

            String[] headerToks = trimmed.split("\\s+");
            if (headerToks.length < 3) continue;
            if (!headerToks[1].equalsIgnoreCase("time")) continue;

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

                double time;
                try { time = Double.parseDouble(tok[1]); }
                catch (NumberFormatException e) { continue; }

                Map<String, Double> rowMap =
                        result.tranData.computeIfAbsent(time, k -> new LinkedHashMap<>());

                for (int col = 2; col < tok.length && col < headerToks.length; col++) {
                    String hdr = headerToks[col].toLowerCase();
                    try {
                        double val = Double.parseDouble(tok[col]);
                        if (hdr.startsWith("v(") || hdr.startsWith("i(")) {
                            rowMap.put(hdr, val);
                        }
                    } catch (NumberFormatException ignored) {}
                }
            }
            break;
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