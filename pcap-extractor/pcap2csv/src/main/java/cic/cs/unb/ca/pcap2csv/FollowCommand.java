package cic.cs.unb.ca.pcap2csv;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class FollowCommand {
    private FollowCommand() { }

    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length != 3 && args.length != 5) {
            usage(err);
            return 2;
        }
        try {
            long seconds = 5;
            if (args.length == 5) {
                if (!"--snapshot-seconds".equals(args[3])) {
                    throw new IllegalArgumentException("Unknown option: " + args[3]);
                }
                seconds = Long.parseLong(args[4]);
                if (seconds < 1 || seconds > 86400) {
                    throw new IllegalArgumentException("snapshot-seconds must be between 1 and 86400");
                }
            }
            Path input = Paths.get(args[1]).toAbsolutePath().normalize();
            Path output = Paths.get(args[2]).toAbsolutePath().normalize();
            final FollowSession session = new FollowSession(input, output, out);
            final AtomicBoolean stopping = new AtomicBoolean();
            Thread shutdownHook = new Thread(() -> {
                stopping.set(true);
                try { session.close(); }
                catch (IOException e) { err.println("Final CSV snapshot failed: " + e.getMessage()); }
            }, "pcap2csv-final-snapshot");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
            try (FollowSession ignored = session) {
                out.println("Following append-only PCAP: " + input);
                out.println("CSV: " + session.outputFile());
                out.println("Snapshot interval: " + seconds + " s. Waiting for complete records; press Ctrl+C to stop.");
                long interval = TimeUnit.SECONDS.toNanos(seconds);
                long nextSnapshot = System.nanoTime();
                while (!stopping.get() && !Thread.currentThread().isInterrupted()) {
                    int count = session.processAvailable();
                    long now = System.nanoTime();
                    if (now - nextSnapshot >= 0) {
                        try {
                            session.snapshot();
                        } catch (IOException e) {
                            err.println("Snapshot not published (will retry): " + e.getMessage());
                        }
                        nextSnapshot = System.nanoTime() + interval;
                    }
                    if (count == 0) {
                        try { Thread.sleep(200); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    }
                }
            } finally {
                try { Runtime.getRuntime().removeShutdownHook(shutdownHook); }
                catch (IllegalStateException ignored) { /* JVM shutdown is already running. */ }
            }
            return 0;
        } catch (IOException | RuntimeException e) {
            err.println("Follow failed: " + e.getMessage());
            return 2;
        }
    }

    static void usage(PrintStream stream) {
        stream.println("Usage: pcap2csv --follow <growing.pcap> <output-directory> [--snapshot-seconds 5]");
    }
}
