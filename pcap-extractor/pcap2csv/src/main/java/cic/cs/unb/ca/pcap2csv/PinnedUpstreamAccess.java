package cic.cs.unb.ca.pcap2csv;

import cic.cs.unb.ca.jnetpcap.BasicFlow;
import cic.cs.unb.ca.jnetpcap.FlowGenerator;
import cic.cs.unb.ca.jnetpcap.PacketReader;
import org.jnetpcap.Pcap;

import java.lang.reflect.Field;
import java.util.HashMap;

/**
 * Narrow adapter for the hash-pinned upstream version, without editing its source.
 * The original classes expose neither a current-flow view nor PacketReader.close.
 * We only read the actual HashMap in its original iteration order, and close the
 * native capture handle after a batch. No flow state or feature formula is changed.
 */
final class PinnedUpstreamAccess {
    private static final Field CURRENT_FLOWS = field(FlowGenerator.class, "currentFlows");
    private static final Field PCAP_READER = field(PacketReader.class, "pcapReader");

    private PinnedUpstreamAccess() { }

    private static Field field(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unsupported CICFlowMeter version: missing " + owner.getSimpleName() + "." + name, e);
        }
    }

    @SuppressWarnings("unchecked")
    static Iterable<BasicFlow> currentFlows(FlowGenerator generator) {
        try {
            return ((HashMap<String, BasicFlow>) CURRENT_FLOWS.get(generator)).values();
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot inspect pinned upstream flow map", e);
        }
    }

    static void close(PacketReader reader) {
        try {
            ((Pcap) PCAP_READER.get(reader)).close();
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot close pinned upstream PCAP handle", e);
        }
    }
}
