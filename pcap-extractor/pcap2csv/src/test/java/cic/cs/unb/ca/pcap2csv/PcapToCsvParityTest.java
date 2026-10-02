package cic.cs.unb.ca.pcap2csv;

import cic.cs.unb.ca.ifm.Cmd;
import cic.cs.unb.ca.jnetpcap.FlowFeature;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PcapToCsvParityTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void extractedCliMatchesOriginalCmdByteForByte() throws Exception {
        File pcap = temporaryFolder.newFile("fixture.pcap");
        writeFixture(pcap);

        File legacyOutput = temporaryFolder.newFolder("legacy");
        File extractedOutput = temporaryFolder.newFolder("extracted");

        Cmd.main(new String[]{pcap.getAbsolutePath(), legacyOutput.getAbsolutePath()});

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exitCode = PcapToCsv.run(
                new String[]{pcap.getAbsolutePath(), extractedOutput.getAbsolutePath()},
                new PrintStream(stdout), new PrintStream(stderr));

        assertEquals(new String(stderr.toByteArray(), StandardCharsets.UTF_8), 0, exitCode);

        File legacyCsv = new File(legacyOutput, "fixture.pcap_Flow.csv");
        File extractedCsv = new File(extractedOutput, "fixture.pcap_Flow.csv");
        assertTrue(legacyCsv.isFile());
        assertTrue(extractedCsv.isFile());
        assertArrayEquals(Files.readAllBytes(legacyCsv.toPath()), Files.readAllBytes(extractedCsv.toPath()));

        String[] lines = new String(Files.readAllBytes(extractedCsv.toPath()), StandardCharsets.UTF_8)
                .split("\\R");
        assertEquals(3, lines.length);
        assertEquals(FlowFeature.getHeader(), lines[0]);
        assertEquals(84, lines[0].split(",", -1).length);
        assertEquals(84, lines[1].split(",", -1).length);
        assertEquals(84, lines[2].split(",", -1).length);
    }

    static void writeFixture(File file) throws Exception {
        DataOutputStream output = new DataOutputStream(new FileOutputStream(file));
        try {
            writeIntLittleEndian(output, 0xa1b2c3d4);
            writeShortLittleEndian(output, 2);
            writeShortLittleEndian(output, 4);
            writeIntLittleEndian(output, 0);
            writeIntLittleEndian(output, 0);
            writeIntLittleEndian(output, 65535);
            writeIntLittleEndian(output, 1);

            byte[] hostA = new byte[]{10, 0, 0, 1};
            byte[] hostB = new byte[]{10, 0, 0, 2};
            writePacket(output, 1, 0, tcpFrame(hostA, hostB, 12345, 80, 0x02, new byte[0]));
            writePacket(output, 1, 10000, tcpFrame(hostB, hostA, 80, 12345, 0x12, new byte[0]));
            writePacket(output, 1, 20000, tcpFrame(hostA, hostB, 12345, 80, 0x10, new byte[0]));
            writePacket(output, 1, 30000, tcpFrame(hostA, hostB, 12345, 80, 0x18,
                    new byte[]{1, 2, 3, 4}));
            writePacket(output, 1, 40000, tcpFrame(hostB, hostA, 80, 12345, 0x10, new byte[0]));

            writePacket(output, 2, 0, udpFrame(hostA, hostB, 53000, 53,
                    new byte[]{10, 11, 12}));
            writePacket(output, 2, 10000, udpFrame(hostB, hostA, 53, 53000,
                    new byte[]{20, 21, 22, 23, 24}));
        } finally {
            output.close();
        }
    }

    static byte[] tcpFrame(byte[] source, byte[] destination, int sourcePort,
                                   int destinationPort, int flags, byte[] payload) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        writeEthernetHeader(output);
        writeIpv4Header(output, source, destination, 6, 20 + payload.length);
        output.writeShort(sourcePort);
        output.writeShort(destinationPort);
        output.writeInt(1);
        output.writeInt(0);
        output.writeByte(0x50);
        output.writeByte(flags);
        output.writeShort(8192);
        output.writeShort(0);
        output.writeShort(0);
        output.write(payload);
        output.flush();
        return bytes.toByteArray();
    }

    static byte[] udpFrame(byte[] source, byte[] destination, int sourcePort,
                                   int destinationPort, byte[] payload) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        writeEthernetHeader(output);
        writeIpv4Header(output, source, destination, 17, 8 + payload.length);
        output.writeShort(sourcePort);
        output.writeShort(destinationPort);
        output.writeShort(8 + payload.length);
        output.writeShort(0);
        output.write(payload);
        output.flush();
        return bytes.toByteArray();
    }

    private static void writeEthernetHeader(DataOutputStream output) throws Exception {
        output.write(new byte[]{0, 1, 2, 3, 4, 5});
        output.write(new byte[]{6, 7, 8, 9, 10, 11});
        output.writeShort(0x0800);
    }

    private static void writeIpv4Header(DataOutputStream output, byte[] source,
                                        byte[] destination, int protocol,
                                        int transportLength) throws Exception {
        output.writeByte(0x45);
        output.writeByte(0);
        output.writeShort(20 + transportLength);
        output.writeShort(1);
        output.writeShort(0x4000);
        output.writeByte(64);
        output.writeByte(protocol);
        output.writeShort(0);
        output.write(source);
        output.write(destination);
    }

    static void writePacket(DataOutputStream output, int seconds, int micros,
                                    byte[] frame) throws Exception {
        writeIntLittleEndian(output, seconds);
        writeIntLittleEndian(output, micros);
        writeIntLittleEndian(output, frame.length);
        writeIntLittleEndian(output, frame.length);
        output.write(frame);
    }

    private static void writeIntLittleEndian(DataOutputStream output, int value) throws Exception {
        output.writeByte(value);
        output.writeByte(value >>> 8);
        output.writeByte(value >>> 16);
        output.writeByte(value >>> 24);
    }

    private static void writeShortLittleEndian(DataOutputStream output, int value) throws Exception {
        output.writeByte(value);
        output.writeByte(value >>> 8);
    }
}
