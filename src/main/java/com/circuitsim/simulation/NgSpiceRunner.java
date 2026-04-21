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
        public List<String> output = new ArrayList<>();
        public String error = null;
        // Keys are lowercase, e.g. "v(1)", "i(vm1)"
        public Map<String, Double> values = new LinkedHashMap<>();

        /** Returns the voltage at the given node number, or "N/A". */
        public String getNodeVoltage(int nodeIndex) {
            if (nodeIndex == 0) return "0.000000 V (Ground)";
            String key = "v(" + nodeIndex + ")";
            if (values.containsKey(key)) {
                return String.format("%.6f V", values.get(key));
            }
            return "N/A";
        }

        /**
         * Returns the current through the named voltage source (e.g. "vm1"), or "N/A".
         * ngspice prints the current as negative when it flows into the + terminal,
         * so we negate it to give the conventional "flowing through the branch" value.
         */
        public String getBranchCurrent(String sourceName) {
            String key = "i(" + sourceName.toLowerCase() + ")";
            if (values.containsKey(key)) {
                return String.format("%.6f A", values.get(key));
            }
            return "N/A";
        }
    }

    public static Result run(String netlist) {
        Result result = new Result();

        Path tempDir;
        Path netlistFile;
        try {
            tempDir = Files.createTempDirectory("circuitsim");
            netlistFile = tempDir.resolve("circuit.cir");
            try (BufferedWriter writer = Files.newBufferedWriter(netlistFile, StandardCharsets.UTF_8)) {
                writer.write(netlist);
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

        // We do NOT use -o here because .control/print output goes to stdout, not the -o file.
        // Redirect stderr to stdout so we capture everything in one stream.
        Process process;
        try {
            ProcessBuilder pb = new ProcessBuilder(executable, "-b", netlistFile.toAbsolutePath().toString());
            pb.redirectErrorStream(true);   // stderr -> stdout
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

        try {
            Files.deleteIfExists(netlistFile);
            Files.deleteIfExists(tempDir);
        } catch (IOException ignored) {}

        String lower = fullOutput.toLowerCase();
        if (lower.contains("fatal") || lower.contains("no dc path")
                || lower.contains("singular matrix")) {
            result.error = "ngspice error:\n" + fullOutput.trim();
            return result;
        }

        parseOutput(fullOutput, result);

        if (result.values.isEmpty()) {
            result.output.add("Simulation completed (no values parsed).");
            result.output.add("--- Raw ngspice output ---");
            for (String line : fullOutput.split("\n")) {
                if (!line.trim().isEmpty()) result.output.add("  " + line.trim());
            }
            return result;
        }

        for (Map.Entry<String, Double> entry : result.values.entrySet()) {
            String key = entry.getKey();
            double val = entry.getValue();
            if (key.startsWith("v(")) {
                result.output.add(String.format("  %s = %.6f V", key, val));
            } else if (key.startsWith("i(")) {
                result.output.add(String.format("  %s = %.6f A", key, val));
            } else {
                result.output.add(String.format("  %s = %g", key, val));
            }
        }

        return result;
    }

    private static String resolveExecutable() {
        for (String candidate : CANDIDATES) {
            try {
                Process p = new ProcessBuilder(candidate, "-v")
                        .redirectErrorStream(true)
                        .start();
                p.getInputStream().readAllBytes();
                boolean finished = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                if (!finished) { p.destroyForcibly(); continue; }
                return candidate;
            } catch (IOException | InterruptedException ignored) {}
        }
        return null;
    }

    /**
     * Parses lines of the form produced by ngspice .control print:
     *
     *   v(1) = 5.000000e+00
     *   i(vm1) = -5.000000e-03
     *
     * Keys are stored lowercase.
     */
    private static void parseOutput(String output, Result result) {
        for (String rawLine : output.split("\n")) {
            String line = rawLine.trim();
            if (!line.contains("=")) continue;

            String[] parts = line.split("=", 2);
            if (parts.length != 2) continue;

            String key = parts[0].trim().toLowerCase();
            String valStr = parts[1].trim();

            // Must look like a known ngspice print key: v(...) or i(...)
            if (!key.startsWith("v(") && !key.startsWith("i(")) continue;

            try {
                double value = Double.parseDouble(valStr);
                result.values.put(key, value);
            } catch (NumberFormatException ignored) {}
        }
    }
}