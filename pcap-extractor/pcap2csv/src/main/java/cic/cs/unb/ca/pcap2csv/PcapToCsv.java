package cic.cs.unb.ca.pcap2csv;

import cic.cs.unb.ca.jnetpcap.Utils;
import cic.cs.unb.ca.jnetpcap.worker.PcapReader;

import java.io.File;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Minimal PCAP-to-flow-CSV command line entry point.
 *
 * The actual packet decoding, flow assembly, feature calculation and CSV
 * serialization are the unmodified CICFlowMeter V4 classes selected by the
 * module build. This class is intentionally limited to argument validation and
 * file/directory dispatch.
 */
public final class PcapToCsv {

    public static final long FLOW_TIMEOUT_MICROS = 120000000L;
    public static final long ACTIVITY_TIMEOUT_MICROS = 5000000L;

    private PcapToCsv() {
    }

    public static void main(String[] args) {
        int result = run(args, System.out, System.err);
        if (result != 0) {
            System.exit(result);
        }
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        if (args != null && args.length > 0 && "--follow".equals(args[0])) {
            return FollowCommand.run(args, out, err);
        }
        if (args == null || args.length != 2) {
            printUsage(err);
            return 2;
        }

        Path input = Paths.get(args[0]).toAbsolutePath().normalize();
        Path output = Paths.get(args[1]).toAbsolutePath().normalize();

        if (!Files.exists(input)) {
            err.println("Input PCAP file or directory does not exist: " + input);
            return 2;
        }

        try {
            Files.createDirectories(output);
        } catch (Exception e) {
            err.println("Cannot create output directory: " + output);
            err.println(e.getMessage());
            return 2;
        }

        if (!Files.isDirectory(output)) {
            err.println("Output path is not a directory: " + output);
            return 2;
        }

        if (Files.isDirectory(input)) {
            return convertDirectory(input.toFile(), output.toString(), out, err);
        }

        if (!Utils.isPcapFile(input.toFile())) {
            err.println("Input is not a classic PCAP file recognized by CICFlowMeter: " + input);
            return 2;
        }

        out.println("CICFlowMeter received 1 PCAP file");
        PcapReader.readFile(input.toString(), output.toString(),
                FLOW_TIMEOUT_MICROS, ACTIVITY_TIMEOUT_MICROS);
        return 0;
    }

    private static int convertDirectory(File inputDirectory, String outputDirectory,
                                        PrintStream out, PrintStream err) {
        File[] pcapFiles = inputDirectory.listFiles(file -> Utils.isPcapFile(file));
        if (pcapFiles == null) {
            err.println("Cannot list input directory: " + inputDirectory.getAbsolutePath());
            return 2;
        }

        out.println("CICFlowMeter found " + pcapFiles.length + " PCAP file(s)");
        for (int i = 0; i < pcapFiles.length; i++) {
            File pcapFile = pcapFiles[i];
            out.println("==> " + (i + 1) + " / " + pcapFiles.length + ": " + pcapFile.getName());
            PcapReader.readFile(pcapFile.getPath(), outputDirectory,
                    FLOW_TIMEOUT_MICROS, ACTIVITY_TIMEOUT_MICROS);
        }
        out.println("Completed!");
        return 0;
    }

    private static void printUsage(PrintStream stream) {
        stream.println("Usage: pcap2csv <input.pcap|input-directory> <output-directory>");
        FollowCommand.usage(stream);
        stream.println("Defaults match CICFlowMeter V4: bidirectional IPv4 flows, "
                + "120 s flow timeout, 5 s activity timeout.");
    }
}
