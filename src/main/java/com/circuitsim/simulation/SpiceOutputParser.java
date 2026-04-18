package com.circuitsim.simulation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class SpiceOutputParser {

    public static List<String> parseResults(String output, Map<Integer, String> probeLabels) {
        List<String> results = new ArrayList<>();
        List<String> lines = output.lines().toList();

        // First pass: look for "key = value" patterns (node voltages and branch currents)
        for (String rawLine : lines) {
            String line = rawLine.trim();

            // Skip headers and non-data lines
            if (line.isEmpty() || line.startsWith("Circuit:") || line.startsWith("Warning:")
                || line.startsWith("Error:") || line.startsWith("*")
                || line.startsWith(".") || line.startsWith("No.")
                || line.startsWith("---") || line.startsWith("===")
                || line.toLowerCase().startsWith("index")
                || line.toLowerCase().startsWith("node")
                || line.toLowerCase().startsWith("initial")) {
                continue;
            }

            if (line.contains("=")) {
                String[] parts = line.split("=", 2);
                if (parts.length == 2) {
                    String key = parts[0].trim();
                    String val = parts[1].trim();

                    try {
                        // Try parsing as node voltage: "1 = 5.000000e+00"
                        int nodeNum = Integer.parseInt(key);
                        double voltage = Double.parseDouble(val);
                        String label = probeLabels.getOrDefault(nodeNum, String.valueOf(nodeNum));
                        results.add(String.format("Node %d (%s): %.6f V", nodeNum, label, voltage));
                    } catch (NumberFormatException e1) {
                        // Try as branch current: "v1#branch = -2.500000e-03"
                        if (key.contains("#branch")) {
                            try {
                                double current = Double.parseDouble(val);
                                results.add(String.format("Branch %s: %.6f A", key, current));
                            } catch (NumberFormatException e2) {
                                // Skip unparseable line
                            }
                        }
                    }
                }
            }
        }

        // Second pass: try tabular format if first pass found nothing
        if (results.isEmpty()) {
            boolean inTable = false;
            List<String> headers = new ArrayList<>();

            for (String rawLine : lines) {
                String line = rawLine.trim();
                if (line.toLowerCase().startsWith("index")) {
                    headers = Arrays.asList(line.split(" +"));
                    inTable = true;
                    continue;
                }
                if (inTable && !line.isEmpty()) {
                    String[] values = line.split(" +");
                    try {
                        Integer.parseInt(values[0].trim());
                        for (int i = 1; i < values.length && i < headers.size(); i++) {
                            String header = headers.get(i);
                            String value = values[i];
                            String lowerHeader = header.toLowerCase();
                            if (lowerHeader.startsWith("v(")) {
                                String nodeStr = header.replace("v(", "").replace("V(", "").replace(")", "");
                                try {
                                    int node = Integer.parseInt(nodeStr);
                                    String label = probeLabels.getOrDefault(node, nodeStr);
                                    results.add(String.format("Node %s (%s): %s V", nodeStr, label, value));
                                } catch (NumberFormatException e) {
                                    results.add(String.format("Voltage %s: %s V", header, value));
                                }
                            } else if (lowerHeader.contains("#branch") || lowerHeader.startsWith("i(")) {
                                results.add(String.format("Current %s: %s A", header, value));
                            }
                        }
                    } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
                        // Skip non-data rows
                    }
                }
            }
        }

        // Third pass: look for "Node voltage" table format
        if (results.isEmpty()) {
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.toLowerCase().contains("node") && line.toLowerCase().contains("voltage")) {
                    // Next lines should be node voltage pairs
                    for (int j = i + 1; j < lines.size(); j++) {
                        String dataLine = lines.get(j).trim();
                        if (dataLine.isEmpty() || dataLine.startsWith("-") || dataLine.startsWith("=")) {
                            continue;
                        }
                        String[] parts = dataLine.split(" +", 2);
                        if (parts.length >= 2) {
                            try {
                                double voltage = Double.parseDouble(parts[parts.length - 1].trim());
                                String nodeName = parts[0].trim();
                                if (!nodeName.contains("#branch")) {
                                    results.add(String.format("%s: %.6f V", nodeName, voltage));
                                } else {
                                    results.add(String.format("%s: %.6f A", nodeName, voltage));
                                }
                            } catch (NumberFormatException e) {
                                // Skip
                            }
                        }
                    }
                    break;
                }
            }
        }

        if (results.isEmpty()) {
            results.add("No simulation results could be parsed from ngspice output.");
            results.add("--- Raw ngspice output ---");
            for (String line : lines) {
                if (!line.trim().isEmpty()) {
                    results.add(line);
                }
            }
        }

        return results;
    }
}