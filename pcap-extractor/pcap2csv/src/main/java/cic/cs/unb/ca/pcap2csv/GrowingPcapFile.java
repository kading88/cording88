package cic.cs.unb.ca.pcap2csv;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Objects;

/** Frames PCAP records only; packet decoding remains entirely in PacketReader. */
final class GrowingPcapFile implements Closeable {
    private static final long MAX_CAPTURED_LENGTH = 16 * 1024 * 1024;
    private final Path path;
    private RandomAccessFile input;
    private Object fileKey;
    private byte[] header;
    private ByteOrder order;
    private long position;
    private long largestObservedLength;

    GrowingPcapFile(Path path) {
        this.path = path;
    }

    boolean isInitialized() { return header != null; }
    long position() { return position; }

    /** Copy only NEW, complete records into a small, valid PCAP batch. */
    int readBatch(Path batchFile, int maxPackets) throws IOException {
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(path, BasicFileAttributes.class);
        } catch (NoSuchFileException e) {
            if (input == null) { return 0; }
            throw new IOException("Input PCAP disappeared; file rotation is not supported: " + path, e);
        }
        if (!attributes.isRegularFile()) {
            throw new IOException("Follow input must be a regular, append-only PCAP file: " + path);
        }
        if (input == null) {
            input = new RandomAccessFile(path.toFile(), "r");
            fileKey = attributes.fileKey();
        } else if (!Objects.equals(fileKey, attributes.fileKey())) {
            throw new IOException("Input PCAP was replaced; use a new session for each capture: " + path);
        }
        long length = input.length();
        if (length < largestObservedLength || length < position) {
            throw new IOException("Input PCAP was truncated; refusing to mix capture sessions: " + path);
        }
        largestObservedLength = length;
        if (length < 24) { return 0; } // The writer may still be writing the global header.

        byte[] currentHeader = new byte[24];
        input.seek(0);
        input.readFully(currentHeader);
        if (header == null) {
            order = byteOrder(currentHeader);
            ByteBuffer fields = ByteBuffer.wrap(currentHeader).order(order);
            if (fields.getShort(4) != 2 || fields.getShort(6) != 4) {
                throw new IOException("Follow mode requires classic PCAP version 2.4");
            }
            header = currentHeader;
            position = 24;
        } else if (!Arrays.equals(header, currentHeader)) {
            throw new IOException("Input PCAP header changed; refusing to mix capture sessions");
        }

        long nextPosition = position;
        int packets = 0;
        byte[] recordHeader = new byte[16];
        byte[] buffer = new byte[64 * 1024];
        OutputStream output = null;
        try {
            while (packets < maxPackets && length - nextPosition >= 16) {
                input.seek(nextPosition);
                input.readFully(recordHeader);
                long captured = Integer.toUnsignedLong(ByteBuffer.wrap(recordHeader).order(order).getInt(8));
                if (captured > MAX_CAPTURED_LENGTH) {
                    throw new IOException("Unreasonable PCAP captured length at byte " + nextPosition + ": " + captured);
                }
                if (length - nextPosition - 16 < captured) { break; }
                if (output == null) {
                    output = new BufferedOutputStream(Files.newOutputStream(batchFile));
                    output.write(header);
                }
                output.write(recordHeader);
                long remaining = captured;
                while (remaining > 0) {
                    int count = (int) Math.min(buffer.length, remaining);
                    input.readFully(buffer, 0, count);
                    output.write(buffer, 0, count);
                    remaining -= count;
                }
                nextPosition += 16 + captured;
                packets++;
            }
        } finally {
            if (output != null) { output.close(); }
        }
        position = nextPosition;
        return packets;
    }

    private static ByteOrder byteOrder(byte[] header) throws IOException {
        int magic = ByteBuffer.wrap(header).getInt();
        if (magic == 0xd4c3b2a1) { return ByteOrder.LITTLE_ENDIAN; }
        if (magic == 0xa1b2c3d4) { return ByteOrder.BIG_ENDIAN; }
        throw new IOException("Follow mode accepts microsecond classic PCAP, not PCAPNG, text or raw sFlow. Use sflowtool -t.");
    }

    @Override public void close() throws IOException {
        if (input != null) { input.close(); }
    }
}
