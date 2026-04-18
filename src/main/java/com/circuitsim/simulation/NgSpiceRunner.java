package com.circuitsim.simulation;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NgSpiceRunner {

    public static class Result {
        public List<String> output = new ArrayList<>();
        public String error = null;
        public Map<String, Double> nodeVoltages = new LinkedHashMap<>();
        public Map<String, Double> branchCurrents = new LinkedHashMap<>();

        public String getNodeVoltage(int nodeIndex) {
            if (nodeIndex == 0) return "0.000 V (Ground)";
            String[] keys = {"N" + nodeIndex, "n" + nodeIndex, String.valueOf(nodeIndex)};
            for (String key : keys) {
                if (nodeVoltages.containsKey(key)) {
                    return String.format("%.6f V", nodeVoltages.get(key));
                }
            }
            for (Map.Entry<String, Double> entry : nodeVoltages.entrySet()) {
                if (entry.getKey().equalsIgnoreCase("n" + nodeIndex)) {
                    return String.format("%.6f V", entry.getValue());
                }
            }
            return "N/A";
        }

        public String getBranchCurrent(String sourceName) {
            // Try exact and case-insensitive match for "sourceName#branch"
            String key = sourceName.toLowerCase() + "#branch";
            for (Map.Entry<String, Double> entry : branchCurrents.entrySet()) {
                if (entry.getKey().toLowerCase().equals(key)
                        || entry.getKey().toLowerCase().equals(sourceName.toLowerCase())) {
                    return String.format("%.6f A", entry.getValue());
                }
            }
            // Also try without #branch suffix
            for (Map.Entry<String, Double> entry : branchCurrents.entrySet()) {
                if (entry.getKey().toLowerCase().contains(sourceName.toLowerCase())) {
                    return String.format("%.6f A", entry.getValue());
                }
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

        // Check ngspice is available
        try {
            Process testProcess = new ProcessBuilder("ngspice", "-v")
                    .redirectErrorStream(true).start();
            boolean finished = testProcess.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                testProcess.destroyForcibly();
                result.error = "ngspice did not respond.";
                return result;
            }
        } catch (IOException e) {
            result.error = "ngspice was not found. Please install ngspice and add it to your PATH.";
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result.error = "ngspice check was interrupted.";
            return result;
        }

        Path outputFile = tempDir.resolve("output.txt");

        // Run ngspice — redirect stderr to NUL/dev/null to suppress info messages
        Process process;
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "ngspice", "-b", "-o",
                    outputFile.toAbsolutePath().toString(),
                    netlistFile.toAbsolutePath().toString()
            );
            // Suppress stderr (the "Information during setup" message comes from stderr)
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            pb.directory(tempDir.toFile());
            process = pb.start();
        } catch (IOException e) {
            result.error = "Failed to start ngspice: " + e.getMessage();
            return result;
        }

        // Capture stdout only
        String stdoutText;
        try {
            stdoutText = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
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

        // Read the -o output file
        String fileText = "";
        if (Files.exists(outputFile)) {
            try {
                fileText = Files.readString(outputFile, StandardCharsets.UTF_8);
            } catch (IOException ignored) {}
        }

        // Use file output as primary, stdout as fallback
        String fullOutput = fileText.isEmpty() ? stdoutText : fileText;

        // Clean up
        try {
            Files.deleteIfExists(netlistFile);
            Files.deleteIfExists(outputFile);
            Files.deleteIfExists(tempDir);
        } catch (IOException ignored) {}

        // Check for fatal errors
        String lower = fullOutput.toLowerCase();
        if (lower.contains("fatal") || lower.contains("no dc path")
                || lower.contains("singular matrix")) {
            result.error = "ngspice error:\n" + fullOutput.trim();
            return result;
        }

        parseOutput(fullOutput, result);

        if (result.nodeVoltages.isEmpty() && result.branchCurrents.isEmpty()) {
            result.output.add("Simulation completed (no output values parsed).");
            result.output.add("--- Raw ngspice output ---");
            for (String line : fullOutput.split("\n")) {
                if (!line.trim().isEmpty()) result.output.add("  " + line.trim());
            }
            return result;
        }

        if (!result.nodeVoltages.isEmpty()) {
            result.output.add("Node Voltages:");
            for (Map.Entry<String, Double> entry : result.nodeVoltages.entrySet()) {
                result.output.add(String.format("  %s = %.6f V", entry.getKey(), entry.getValue()));
            }
        }
        if (!result.branchCurrents.isEmpty()) {
            result.output.add("Branch Currents:");
            for (Map.Entry<String, Double> entry : result.branchCurrents.entrySet()) {
                result.output.add(String.format("  %s = %.6f A", entry.getKey(), entry.getValue()));
            }
        }

        return result;
    }

    private static void parseOutput(String output, Result result) {
        String[] lines = output.split("\n");

        Pattern sciPattern = Pattern.compile(
                "^\\s*([a-zA-Z][a-zA-Z0-9_#]*)\\s+([-+]?[0-9]*\\.?[0-9]+[eE][-+]?[0-9]+)\\s*$");
        Pattern decPattern = Pattern.compile(
                "^\\s*([a-zA-Z][a-zA-Z0-9_#]*)\\s+([-+]?[0-9]+\\.?[0-9]*)\\s*$");

        boolean inNodeSection = false;
        boolean inCurrentSection = false;

        for (String rawLine : lines) {
            String line = rawLine.trim();

            if (line.toLowerCase().contains("node") && line.toLowerCase().contains("voltage")) {
                inNodeSection = true;
                inCurrentSection = false;
                continue;
            }
            if (line.toLowerCase().contains("voltage source") && line.toLowerCase().contains("current")) {
                inCurrentSection = true;
                inNodeSection = false;
                continue;
            }
            if (line.isEmpty()) {
                inNodeSection = false;
                inCurrentSection = false;
                continue;
            }

            if (inNodeSection || inCurrentSection) {
                String[] parts = line.split("\\s+");
                if (parts.length >= 2) {
                    try {
                        double value = Double.parseDouble(parts[parts.length - 1]);
                        String name = parts[0].toLowerCase();
                        if (inNodeSection && !name.contains("#")) {
                            result.nodeVoltages.put(name, value);
                        } else if (name.contains("#branch")) {
                            result.branchCurrents.put(name, value);
                        }
                    } catch (NumberFormatException ignored) {}
                }
            }

            // v(N) = value from .PRINT output
            Pattern printPattern = Pattern.compile(
                    "v\\(([^)]+)\\)\\s+([-+]?[0-9]*\\.?[0-9]+(?:[eE][-+]?[0-9]+)?)");
            Matcher m = printPattern.matcher(line.toLowerCase());
            if (m.find()) {
                try {
                    result.nodeVoltages.put("n" + m.group(1), Double.parseDouble(m.group(2)));
                } catch (NumberFormatException ignored) {}
            }

            // i(VMx) = value from .PRINT output
            Pattern currentPrintPattern = Pattern.compile(
                    "i\\(([^)]+)\\)\\s+([-+]?[0-9]*\\.?[0-9]+(?:[eE][-+]?[0-9]+)?)");
            Matcher cm = currentPrintPattern.matcher(line.toLowerCase());
            if (cm.find()) {
                try {
                    result.branchCurrents.put(cm.group(1), Double.parseDouble(cm.group(2)));
                } catch (NumberFormatException ignored) {}
            }

            // key = value format
            if (line.contains("=")) {
                String[] parts = line.split("=", 2);
                if (parts.length == 2) {
                    String key = parts[0].trim().toLowerCase();
                    try {
                        double d = Double.parseDouble(parts[1].trim());
                        if (key.contains("#branch")) {
                            result.branchCurrents.put(key, d);
                        } else if (!key.isEmpty() && !key.contains(" ")) {
                            result.nodeVoltages.put(key, d);
                        }
                    } catch (NumberFormatException ignored) {}
                }
            }
        }
    }
}