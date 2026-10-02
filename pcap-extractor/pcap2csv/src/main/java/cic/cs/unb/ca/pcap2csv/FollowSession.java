package cic.cs.unb.ca.pcap2csv;

import cic.cs.unb.ca.jnetpcap.BasicFlow;
import cic.cs.unb.ca.jnetpcap.BasicPacketInfo;
import cic.cs.unb.ca.jnetpcap.FlowFeature;
import cic.cs.unb.ca.jnetpcap.FlowGenerator;
import cic.cs.unb.ca.jnetpcap.PacketReader;
import org.jnetpcap.PcapClosedException;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

import static cic.cs.unb.ca.jnetpcap.Utils.FLOW_SUFFIX;
import static cic.cs.unb.ca.jnetpcap.Utils.LINE_SEP;

/** One append-only PCAP capture, one persistent upstream FlowGenerator. */
final class FollowSession implements Closeable {
    private final GrowingPcapFile input;
    private final FlowGenerator generator;
    private final Path workDirectory;
    private final Path batchFile;
    private final Path completedFile;
    private final Path outputFile;
    private final Path reservationFile;
    private final PrintStream out;
    private long packets;
    private long validPackets;
    private long completedFlows;
    private long publishedPackets = -1;
    private boolean closed;
    private boolean failed;

    FollowSession(Path inputFile, Path outputDirectory, PrintStream out) throws IOException {
        this.out = out;
        Files.createDirectories(outputDirectory);
        outputFile = outputDirectory.resolve(inputFile.getFileName().toString() + FLOW_SUFFIX);
        if (Files.exists(outputFile)) {
            throw new IOException("Follow output already exists; choose a new output directory: " + outputFile);
        }
        // CREATE_NEW also protects the waiting-for-input phase, before the CSV
        // exists. Two followers must not publish into the same output path.
        reservationFile = outputDirectory.resolve(outputFile.getFileName() + ".follow.lock");
        Files.createFile(reservationFile);
        boolean initialized = false;
        try {
            workDirectory = Files.createTempDirectory(outputDirectory, ".pcap2csv-follow-");
            batchFile = workDirectory.resolve("batch.pcap");
            completedFile = workDirectory.resolve("completed.csv");
            Files.write(completedFile, (FlowFeature.getHeader() + LINE_SEP).getBytes(), StandardOpenOption.CREATE_NEW);
            input = new GrowingPcapFile(inputFile);
            generator = new FlowGenerator(true, PcapToCsv.FLOW_TIMEOUT_MICROS, PcapToCsv.ACTIVITY_TIMEOUT_MICROS);
            generator.addFlowListener(flow -> {
                try {
                    Files.write(completedFile, (flow.dumpFlowBasedFeaturesEx() + LINE_SEP).getBytes(), StandardOpenOption.APPEND);
                    completedFlows++;
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            initialized = true;
        } finally {
            if (!initialized) { Files.deleteIfExists(reservationFile); }
        }
    }

    Path outputFile() { return outputFile; }
    synchronized long packetCount() { return packets; }
    synchronized long consumedBytes() { return input.position(); }

    /** All native decoding and all flow mutation happen on the calling thread. */
    synchronized int processAvailable() throws IOException {
        if (closed) { return 0; }
        try {
            int count = input.readBatch(batchFile, 4096);
            if (count == 0) { return 0; }
            PacketReader reader = new PacketReader(batchFile.toString(), true, false);
            int read = 0;
            try {
                while (true) {
                    try {
                        BasicPacketInfo packet = reader.nextPacket();
                        read++;
                        if (packet != null) {
                            generator.addPacket(packet);
                            validPackets++;
                        }
                        packets++;
                    } catch (PcapClosedException end) {
                        break;
                    }
                }
            } finally {
                PinnedUpstreamAccess.close(reader);
            }
            if (read != count) {
                throw new IOException("Original decoder stopped early: expected " + count + " PCAP records, read " + read);
            }
            return count;
        } catch (IOException | RuntimeException e) {
            failed = true;
            throw e;
        }
    }

    /** Publish the same rows an offline EOF at the processed prefix would emit. */
    synchronized boolean snapshot() throws IOException {
        if (closed || failed || !input.isInitialized() || packets == publishedPackets) { return false; }
        Path staging = Files.createTempFile(workDirectory, "snapshot-", ".csv");
        long currentFlows = 0;
        try {
            Files.copy(completedFile, staging, StandardCopyOption.REPLACE_EXISTING);
            try (OutputStream stream = new BufferedOutputStream(Files.newOutputStream(staging, StandardOpenOption.APPEND))) {
                for (BasicFlow flow : PinnedUpstreamAccess.currentFlows(generator)) {
                    // This is the upstream EOF filter, including its singleton behavior.
                    if (flow.packetCount() > 1) {
                        stream.write((flow.dumpFlowBasedFeaturesEx() + LINE_SEP).getBytes());
                        currentFlows++;
                    }
                }
            }
            try {
                Files.move(staging, outputFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                throw new IOException("Output filesystem must support atomic replacement; previous CSV was kept: " + outputFile, e);
            }
            publishedPackets = packets;
            out.println("Snapshot: packets=" + packets + ", valid=" + validPackets
                    + ", flows=" + (completedFlows + currentFlows) + ", PCAP bytes=" + input.position()
                    + ", CSV=" + outputFile);
            return true;
        } finally {
            Files.deleteIfExists(staging);
        }
    }

    @Override public synchronized void close() throws IOException {
        if (closed) { return; }
        try {
            // Errors in the input/engine retain the last known-good published CSV.
            snapshot();
        } finally {
            closed = true;
            try { input.close(); }
            finally { Files.deleteIfExists(reservationFile); }
        }
        // Keep the small batch and completed-flow journal for diagnosis, inside
        // the chosen output directory. Never delete or modify the source PCAP.
    }
}
