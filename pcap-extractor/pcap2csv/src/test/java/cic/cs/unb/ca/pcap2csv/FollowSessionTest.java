package cic.cs.unb.ca.pcap2csv;

import cic.cs.unb.ca.ifm.Cmd;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static cic.cs.unb.ca.pcap2csv.PcapToCsvParityTest.tcpFrame;
import static cic.cs.unb.ca.pcap2csv.PcapToCsvParityTest.udpFrame;
import static cic.cs.unb.ca.pcap2csv.PcapToCsvParityTest.writePacket;
import static org.junit.Assert.*;

public class FollowSessionTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final PrintStream quiet = new PrintStream(new ByteArrayOutputStream());

    private byte[] fixture() throws Exception {
        Path file = temporary.newFile().toPath();
        PcapToCsvParityTest.writeFixture(file.toFile());
        return Files.readAllBytes(file);
    }

    private byte[] original(byte[] prefix) throws Exception {
        Path directory = temporary.newFolder().toPath();
        Path file = directory.resolve("prefix.pcap");
        Path output = Files.createDirectory(directory.resolve("oracle"));
        Files.write(file, prefix);
        Cmd.main(new String[]{file.toString(), output.toString()});
        return Files.readAllBytes(output.resolve("prefix.pcap_Flow.csv"));
    }

    @Test public void partialWritesAreWaitedForAndEveryPrefixMatchesOriginal() throws Exception {
        byte[] bytes = fixture();
        Path growing = temporary.newFile("growing.pcap").toPath();
        try (FollowSession session = new FollowSession(growing, temporary.newFolder().toPath(), quiet)) {
            long previousPackets = -1;
            for (int i = 0; i < bytes.length; i++) {
                Files.write(growing, new byte[]{bytes[i]}, StandardOpenOption.APPEND);
                session.processAvailable();
                if (i == 23 || session.packetCount() != previousPackets) {
                    if (session.snapshot()) {
                        assertArrayEquals("prefix ending at " + session.consumedBytes(),
                                original(Arrays.copyOf(bytes, (int) session.consumedBytes())),
                                Files.readAllBytes(session.outputFile()));
                    }
                    previousPackets = session.packetCount();
                }
            }
            assertEquals(7, session.packetCount());
            assertEquals(bytes.length, session.consumedBytes());
            assertEquals(0, session.processAvailable());
            assertFalse("An unchanged snapshot must not duplicate rows", session.snapshot());
        }
    }

    @Test public void followsAFileThatDoesNotExistYet() throws Exception {
        Path growing = temporary.getRoot().toPath().resolve("later.pcap");
        byte[] bytes = fixture();
        try (FollowSession session = new FollowSession(growing, temporary.newFolder().toPath(), quiet)) {
            assertEquals(0, session.processAvailable());
            Files.write(growing, bytes);
            assertEquals(7, session.processAvailable());
            assertTrue(session.snapshot());
            assertArrayEquals(original(bytes), Files.readAllBytes(session.outputFile()));
        }
    }

    @Test public void flowStateSurvivesBatchesFinRstAndTimeouts() throws Exception {
        byte[] a = {10, 0, 0, 1}, b = {10, 0, 0, 2};
        ByteArrayOutputStream capture = new ByteArrayOutputStream();
        capture.write(Arrays.copyOf(fixture(), 24));
        DataOutputStream packets = new DataOutputStream(capture);
        writePacket(packets, 1, 0, tcpFrame(a, b, 40000, 443, 0x02, new byte[0]));
        writePacket(packets, 1, 100, tcpFrame(b, a, 443, 40000, 0x12, new byte[0]));
        writePacket(packets, 1, 230, tcpFrame(a, b, 40000, 443, 0x18, new byte[13]));
        writePacket(packets, 1, 330, tcpFrame(a, b, 40000, 443, 0x11, new byte[0]));
        writePacket(packets, 1, 440, tcpFrame(b, a, 443, 40000, 0x11, new byte[0]));
        writePacket(packets, 2, 0, udpFrame(a, b, 53000, 53, new byte[3]));
        writePacket(packets, 2, 10, udpFrame(b, a, 53, 53000, new byte[11]));
        writePacket(packets, 123, 0, udpFrame(b, a, 53, 53000, new byte[7]));
        writePacket(packets, 123, 20, udpFrame(a, b, 53000, 53, new byte[17]));
        writePacket(packets, 125, 0, tcpFrame(a, b, 50000, 80, 0x04, new byte[0]));
        writePacket(packets, 125, 100, tcpFrame(b, a, 80, 50000, 0x04, new byte[1]));
        verifyRecordByRecord(capture.toByteArray());
    }

    @Test public void hashCollisionOrderingRemainsOriginalAcrossSnapshots() throws Exception {
        byte[] a = {10, 0, 0, 1}, b = {10, 0, 0, 2};
        int[] ports = {1125, 1253, 1288, 1423, 1471, 1538, 1551, 1586, 1708, 1721, 1836, 1884};
        ByteArrayOutputStream capture = new ByteArrayOutputStream();
        capture.write(Arrays.copyOf(fixture(), 24));
        DataOutputStream packets = new DataOutputStream(capture);
        for (int port : ports) {
            writePacket(packets, 1, 0, udpFrame(a, b, port, 53, new byte[16]));
            writePacket(packets, 1, 10000, udpFrame(a, b, port, 53, new byte[16]));
        }
        verifyRecordByRecord(capture.toByteArray());
    }

    @Test public void bigEndianPcapHasOriginalByteParity() throws Exception {
        byte[] little = fixture();
        byte[] big = little.clone();
        ByteBuffer source = ByteBuffer.wrap(little).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer target = ByteBuffer.wrap(big).order(ByteOrder.BIG_ENDIAN);
        target.putInt(0, source.getInt(0));
        target.putShort(4, source.getShort(4));
        target.putShort(6, source.getShort(6));
        for (int i = 8; i < 24; i += 4) { target.putInt(i, source.getInt(i)); }
        for (int offset = 24; offset < big.length;) {
            for (int i = 0; i < 16; i += 4) { target.putInt(offset + i, source.getInt(offset + i)); }
            offset += 16 + source.getInt(offset + 8);
        }
        verifyRecordByRecord(big);
    }

    @Test public void sampledTruncatedAndEncapsulatedPacketsUseTheOriginalDecoder() throws Exception {
        byte[] a = {10, 0, 0, 1}, b = {10, 0, 0, 2};
        List<byte[]> frames = new ArrayList<>();
        byte[] tcp = tcpFrame(a, b, 23456, 443, 0x10, new byte[1000]);
        frames.add(Arrays.copyOf(tcp, 128)); // sFlow captured header shorter than the full packet.
        byte[] tcpOptions = tcpFrame(a, b, 23456, 443, 0x10, new byte[16]);
        tcpOptions[46] = (byte) 0xf0;
        frames.add(tcpOptions); // Truncated TCP options must keep the upstream interpretation.
        byte[] udp = udpFrame(a, b, 30000, 53, new byte[16]);
        byte[] shortUdp = udp.clone();
        shortUdp[38] = 0;
        shortUdp[39] = 8;
        frames.add(shortUdp);
        byte[] vlan = new byte[udp.length + 14]; // 4-byte tag plus 10 padding bytes.
        System.arraycopy(udp, 0, vlan, 0, 12);
        System.arraycopy(new byte[]{(byte) 0x81, 0, 0, 1, 8, 0}, 0, vlan, 12, 6);
        System.arraycopy(udp, 14, vlan, 18, udp.length - 14);
        frames.add(vlan);
        byte[] inner = Arrays.copyOfRange(tcpFrame(a, b, 12345, 80, 0x10, new byte[16]), 14, 70);
        byte[] ipip = new byte[34 + inner.length];
        System.arraycopy(udp, 0, ipip, 0, 34);
        ipip[23] = 4;
        ByteBuffer.wrap(ipip).putShort(16, (short) (20 + inner.length));
        System.arraycopy(inner, 0, ipip, 34, inner.length);
        frames.add(ipip);
        byte[] l2tp = new byte[10 + inner.length];
        System.arraycopy(new byte[]{0, 2, 0, 1, 0, 1, (byte) 0xff, 3, 0, 0x21}, 0, l2tp, 0, 10);
        System.arraycopy(inner, 0, l2tp, 10, inner.length);
        frames.add(udpFrame(a, b, 1701, 1701, l2tp));
        byte[] fragment = udp.clone();
        fragment[20] = 0;
        fragment[21] = 1;
        frames.add(fragment);

        ByteArrayOutputStream capture = new ByteArrayOutputStream();
        capture.write(Arrays.copyOf(fixture(), 24));
        DataOutputStream packets = new DataOutputStream(capture);
        int index = 0;
        for (byte[] frame : frames) {
            writePacket(packets, 1, index++ * 100, frame);
            writePacket(packets, 1, index++ * 100, frame);
        }
        byte[] bytes = capture.toByteArray();
        // Preserve the original wire length in both sampled TCP records.
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(24 + 12, tcp.length);
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(24 + 16 + 128 + 12, tcp.length);
        verifyRecordByRecord(bytes);
    }

    @Test public void moreThanOneNativeBatchPreservesAllPacketStatistics() throws Exception {
        byte[] a = {10, 0, 0, 1}, b = {10, 0, 0, 2};
        ByteArrayOutputStream capture = new ByteArrayOutputStream();
        capture.write(Arrays.copyOf(fixture(), 24));
        DataOutputStream writer = new DataOutputStream(capture);
        for (int i = 0; i < 5000; i++) {
            writePacket(writer, 1 + i / 1000, (i % 1000) * 1000,
                    udpFrame(i % 2 == 0 ? a : b, i % 2 == 0 ? b : a,
                            i % 2 == 0 ? 30000 : 53, i % 2 == 0 ? 53 : 30000, new byte[i % 137]));
        }
        byte[] bytes = capture.toByteArray();
        Path input = temporary.newFile("large.pcap").toPath();
        Files.write(input, bytes);
        try (FollowSession session = new FollowSession(input, temporary.newFolder().toPath(), quiet)) {
            assertEquals(4096, session.processAvailable());
            session.snapshot();
            assertArrayEquals(original(Arrays.copyOf(bytes, (int) session.consumedBytes())), Files.readAllBytes(session.outputFile()));
            assertEquals(904, session.processAvailable());
            session.snapshot();
            assertArrayEquals(original(bytes), Files.readAllBytes(session.outputFile()));
            assertEquals(0, session.processAvailable());
            assertEquals(5000, session.packetCount());
        }
    }

    @Test public void truncationStopsWithoutReplacingLastGoodSnapshot() throws Exception {
        Path growing = temporary.newFile("truncated.pcap").toPath();
        Files.write(growing, fixture());
        Path output;
        byte[] previous;
        try (FollowSession session = new FollowSession(growing, temporary.newFolder().toPath(), quiet)) {
            session.processAvailable();
            session.snapshot();
            output = session.outputFile();
            previous = Files.readAllBytes(output);
            try (RandomAccessFile writer = new RandomAccessFile(growing.toFile(), "rw")) { writer.setLength(24); }
            try {
                session.processAvailable();
                fail("Expected truncation error");
            } catch (IOException expected) { assertTrue(expected.getMessage().contains("truncated")); }
        }
        assertArrayEquals(previous, Files.readAllBytes(output));
    }

    @Test public void malformedPcapLengthIsRejectedBeforeAllocation() throws Exception {
        byte[] bytes = Arrays.copyOf(fixture(), 40);
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(32, 0x7fffffff);
        Path growing = temporary.newFile("invalid.pcap").toPath();
        Files.write(growing, bytes);
        try (FollowSession session = new FollowSession(growing, temporary.newFolder().toPath(), quiet)) {
            try {
                session.processAvailable();
                fail("Expected invalid captured length");
            } catch (IOException expected) { assertTrue(expected.getMessage().contains("captured length")); }
        }
    }

    @Test public void anExistingCsvIsNeverOverwrittenOnStartup() throws Exception {
        Path input = temporary.getRoot().toPath().resolve("input.pcap");
        Path directory = temporary.newFolder().toPath();
        Path existing = directory.resolve("input.pcap_Flow.csv");
        byte[] sentinel = {1, 2, 3};
        Files.write(existing, sentinel);
        try {
            new FollowSession(input, directory, quiet);
            fail("Expected output collision error");
        } catch (IOException expected) { assertTrue(expected.getMessage().contains("already exists")); }
        assertArrayEquals(sentinel, Files.readAllBytes(existing));
    }

    @Test public void anotherFollowerCannotReserveTheSameOutputWhileWaiting() throws Exception {
        Path input = temporary.getRoot().toPath().resolve("waiting.pcap");
        Path output = temporary.newFolder().toPath();
        try (FollowSession first = new FollowSession(input, output, quiet)) {
            assertEquals(0, first.processAvailable());
            try {
                new FollowSession(input, output, quiet);
                fail("Expected exclusive output reservation");
            } catch (IOException expected) {
                assertFalse(Files.exists(first.outputFile()));
            }
        }
        try (FollowSession afterClose = new FollowSession(input, output, quiet)) {
            assertEquals(0, afterClose.processAvailable());
        }
    }

    @Test public void commandFollowsAppendsAndStopsWhenInterrupted() throws Exception {
        byte[] bytes = fixture();
        byte[] want = original(bytes);
        Path input = temporary.newFile("cli.pcap").toPath();
        Path output = temporary.newFolder().toPath();
        Files.write(input, Arrays.copyOf(bytes, 24));
        AtomicInteger result = new AtomicInteger(-1);
        Thread watcher = new Thread(() -> result.set(PcapToCsv.run(new String[]{
                "--follow", input.toString(), output.toString(), "--snapshot-seconds", "1"}, quiet, quiet)));
        watcher.start();
        Path csv = output.resolve("cli.pcap_Flow.csv");
        try {
            waitForFile(csv, null);
            Files.write(input, Arrays.copyOfRange(bytes, 24, bytes.length), StandardOpenOption.APPEND);
            waitForFile(csv, want);
        } finally {
            watcher.interrupt();
            watcher.join(5000);
        }
        assertFalse("Watcher did not stop", watcher.isAlive());
        assertEquals(0, result.get());
        assertArrayEquals(want, Files.readAllBytes(csv));
    }

    @Test public void invalidFollowOptionsReturnAnErrorWithoutStarting() {
        assertEquals(2, PcapToCsv.run(new String[]{"--follow"}, quiet, quiet));
        assertEquals(2, PcapToCsv.run(new String[]{"--follow", "x", "y", "--snapshot-seconds", "0"}, quiet, quiet));
        assertEquals(2, PcapToCsv.run(new String[]{"--follow", "x", "y", "--snapshot-seconds", "no"}, quiet, quiet));
        assertEquals(2, PcapToCsv.run(new String[]{"--follow", "x", "y", "--unknown", "1"}, quiet, quiet));
    }

    private void verifyRecordByRecord(byte[] capture) throws Exception {
        Path input = temporary.newFile().toPath();
        Files.write(input, Arrays.copyOf(capture, 24));
        ByteOrder order = capture[0] == (byte) 0xd4 ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
        List<Integer> ends = new ArrayList<>();
        for (int offset = 24; offset < capture.length;) {
            offset += 16 + ByteBuffer.wrap(capture).order(order).getInt(offset + 8);
            ends.add(offset);
        }
        try (FollowSession session = new FollowSession(input, temporary.newFolder().toPath(), quiet)) {
            int start = 24;
            for (int end : ends) {
                Files.write(input, Arrays.copyOfRange(capture, start, end), StandardOpenOption.APPEND);
                assertEquals(1, session.processAvailable());
                assertTrue(session.snapshot());
                assertArrayEquals(original(Arrays.copyOf(capture, end)), Files.readAllBytes(session.outputFile()));
                start = end;
            }
            assertEquals(ends.size(), session.packetCount());
        }
    }

    private static void waitForFile(Path file, byte[] expected) throws Exception {
        long deadline = System.nanoTime() + 8_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (Files.exists(file) && (expected == null || Arrays.equals(expected, Files.readAllBytes(file)))) { return; }
            Thread.sleep(20);
        }
        fail("Timed out waiting for CSV snapshot: " + file);
    }
}
