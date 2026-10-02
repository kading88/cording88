# Follow an append-only PCAP file

`--follow` reads complete records as they are appended to a classic PCAP file and periodically publishes a full CSV snapshot. It can wait for a file to appear and for partially written headers or packet records to finish. The default snapshot interval is five seconds.

The source must be append-only. File truncation, replacement, rotation, or header changes stop the session. Follow mode is a file reader, not a TCP service, a capture adapter, or a standard-input reader.

## Windows

From the `pcap-extractor` directory with JDK 8 selected:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\pcap2csv\run.ps1 -Follow -InputPath 'C:\captures\live.pcap' -OutputDirectory 'C:\captures\live-csv' -SnapshotSeconds 5
```

Use a new output directory. Follow mode refuses to overwrite an existing target CSV. The interval accepts integers from 1 to 86,400 seconds.

## Linux and sflowtool

Build on the Linux collector using JDK 8 and libpcap:

```bash
bash ./gradlew -p pcap2csv installDist --no-daemon
```

Start sflowtool in one terminal:

```bash
mkdir -p captures
set -o noclobber
sflowtool -p 6343 -t > captures/live.pcap
```

Then start the Java reader in another terminal:

```bash
./pcap2csv/build/install/pcap2csv/bin/pcap2csv \
  --follow ./captures/live.pcap ./live-csv \
  --snapshot-seconds 5
```

The input must be the binary PCAP produced by `sflowtool -t`, rather than its text, JSON, raw sFlow forwarding, or outer-packet capture output. The Java process follows the local file and does not bind to sflowtool's UDP port. Windows builds contain DLLs; build a Linux distribution on Linux instead of copying the Windows distribution.

The original native decode and compatibility checks were performed on Windows with JDK 8. Linux commands follow the build's platform-selection logic but have not been validated on an actual Linux collector in this delivery.

## Snapshot semantics

- Every CSV is a full snapshot through the last complete processed PCAP record, not an incremental batch of new rows.
- The file retains the original 84 columns and `NeedManualLabel` placeholder.
- Packets from successive batches use the same flow generator. A snapshot does not reset flows.
- Active flow statistics can change when more packets arrive. A single-packet active flow may not appear under the original end-of-file rules.
- The snapshot interval differs from the original 120-second flow timeout and five-second activity threshold. Snapshots do not add a wall-clock expiry policy.
- A temporary file is atomically moved over the target CSV. If another application locks the file, the old snapshot remains available while the write is retried.
- Console output reports processing progress, including records, valid packets, flow counts, and PCAP byte position.

The adapter preserves the upstream feature formulas and uses reflection only where upstream lacks accessors for current flows and packet-reader cleanup. Compatibility checks compare snapshot bytes with the original offline pipeline processing the corresponding complete PCAP prefix.

## Stop and restart

Ctrl+C attempts a final snapshot. Forced termination or a power loss cannot guarantee that final write. To restart, choose a new output directory and replay the same append-only file from its beginning.

A `.follow.lock` file prevents two readers from publishing to the same target. Normal shutdown removes it. A forced stop can leave it behind; starting a new output directory avoids reusing a stale session.

Internal `.pcap2csv-follow-*` directories contain batch files and closed-flow logs. Long runs remain limited by memory, disk space, and the upstream flow-retention behavior.

The extractor cannot recover unsampled packets or original timestamps from sampled sFlow data. Training and inference need compatible feature definitions and capture conditions.

Reference: [sflowtool usage](https://github.com/sflow/sflowtool#usage-examples).
