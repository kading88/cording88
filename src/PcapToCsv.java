import cic.cs.unb.ca.jnetpcap.BasicFlow;
import cic.cs.unb.ca.jnetpcap.BasicPacketInfo;
import cic.cs.unb.ca.jnetpcap.FlowFeature;
import cic.cs.unb.ca.jnetpcap.FlowGenerator;
import cic.cs.unb.ca.jnetpcap.PacketReader;
import org.jnetpcap.Pcap;
import org.jnetpcap.PcapClosedException;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;

/** Offline PCAP extraction using the pinned CICFlowMeter feature implementation. */
public final class PcapToCsv {
    public static void main(String[] args) {
        if (args.length < 2 || args.length > 3) {
            System.err.println("Usage: PcapToCsv input.pcap output-directory [mysql.properties]");
            System.exit(2);
        }
        try {
            Path input = Paths.get(args[0]).toAbsolutePath().normalize();
            Path outputDir = Paths.get(args[1]).toAbsolutePath().normalize();
            long packets = validatePcap(input);
            Files.createDirectories(outputDir);
            Path csv = outputDir.resolve(input.getFileName() + "_Flow.csv");
            Path temporary = Files.createTempFile(outputDir, "pcap-", ".tmp");
            try {
                long flows = extract(input, temporary);
                Files.move(temporary, csv, StandardCopyOption.REPLACE_EXISTING);
                System.out.println("Input: " + input.getFileName());
                System.out.println("Packets: " + packets);
                System.out.println("CSV: " + csv);
                System.out.println("Flows: " + flows + "; columns: 84; label: NeedManualLabel");
            } finally {
                Files.deleteIfExists(temporary);
            }
            if (args.length == 3) {
                MySqlStore.save(csv, input.getFileName().toString(), Paths.get(args[2]));
            }
        } catch (Exception | LinkageError error) {
            // Do not print connection properties or credentials in error output.
            System.err.println("Failed: " + error.getClass().getSimpleName());
            if (error instanceof java.sql.SQLException) {
                java.sql.SQLException sql = (java.sql.SQLException) error;
                System.err.println("MySQL SQLState=" + sql.getSQLState() + ", code=" + sql.getErrorCode());
                System.err.println("Check the database service, account, permissions, and configuration. CSV is retained.");
            } else if (error instanceof LinkageError) {
                System.err.println("Use a 64-bit JDK 8 and install the Windows WinPcap-compatible capture runtime.");
            } else {
                System.err.println(error.getMessage());
            }
            System.exit(1);
        }
    }

    private static long extract(Path input, Path csv) throws Exception {
        // Preserve the original flow and activity timeouts, both in microseconds.
        FlowGenerator generator = new FlowGenerator(true, 120000000L, 5000000L);
        long[] flowCount = {0};
        try (BufferedWriter writer = Files.newBufferedWriter(csv, StandardCharsets.UTF_8)) {
            writer.write(FlowFeature.getHeader());
            writer.newLine();
            generator.addFlowListener(flow -> writeFlow(writer, flow, flowCount));
            PacketReader reader = new PacketReader(input.toString(), true, false);
            try {
                while (true) {
                    try {
                        BasicPacketInfo packet = reader.nextPacket();
                        if (packet != null) generator.addPacket(packet);
                    } catch (PcapClosedException endOfFile) {
                        break;
                    }
                }
                // The pinned upstream API has no public flush/close methods.
                // Access its state only here; feature calculations stay unchanged.
                Field flowsField = FlowGenerator.class.getDeclaredField("currentFlows");
                flowsField.setAccessible(true);
                @SuppressWarnings("unchecked")
                Map<String, BasicFlow> remaining = (Map<String, BasicFlow>) flowsField.get(generator);
                for (BasicFlow flow : remaining.values()) {
                    if (flow.packetCount() > 1) writeFlow(writer, flow, flowCount);
                }
            } finally {
                Field handleField = PacketReader.class.getDeclaredField("pcapReader");
                handleField.setAccessible(true);
                ((Pcap) handleField.get(reader)).close();
            }
        }
        return flowCount[0];
    }

    private static void writeFlow(BufferedWriter writer, BasicFlow flow, long[] count) {
        try {
            writer.write(flow.dumpFlowBasedFeaturesEx());
            writer.newLine();
            count[0]++;
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    /** Reject unsupported headers and incomplete records before native parsing. */
    private static long validatePcap(Path path) throws IOException {
        if (!Files.isRegularFile(path)) throw new IOException("Input PCAP does not exist.");
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
            if (file.length() < 24) throw new IOException("Incomplete PCAP header.");
            int magic = file.readInt();
            ByteOrder order;
            if (magic == 0xd4c3b2a1) order = ByteOrder.LITTLE_ENDIAN;
            else if (magic == 0xa1b2c3d4) order = ByteOrder.BIG_ENDIAN;
            else throw new IOException("Expected classic microsecond PCAP; PCAPNG is not supported.");
            byte[] header = new byte[20];
            file.readFully(header);
            ByteBuffer buffer = ByteBuffer.wrap(header).order(order);
            if (buffer.getShort(0) != 2 || buffer.getShort(2) != 4 || buffer.getInt(16) != 1)
                throw new IOException("Expected PCAP 2.4 with Ethernet frames.");
            long snapLength = Integer.toUnsignedLong(buffer.getInt(12));
            long count = 0;
            byte[] record = new byte[16];
            while (file.getFilePointer() < file.length()) {
                if (file.length() - file.getFilePointer() < 16) throw new IOException("Incomplete packet header.");
                file.readFully(record);
                long length = Integer.toUnsignedLong(ByteBuffer.wrap(record).order(order).getInt(8));
                if (length > snapLength || length > file.length() - file.getFilePointer())
                    throw new IOException("Invalid or incomplete packet data.");
                file.seek(file.getFilePointer() + length);
                count++;
            }
            return count;
        }
    }
}
