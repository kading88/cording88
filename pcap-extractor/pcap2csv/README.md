# CICFlowMeter PCAP-to-CSV Extractor

A standalone command-line entry point for CICFlowMeter V4's existing feature pipeline. It reads classic PCAP files, groups packets into bidirectional flows, and writes `<input-name>_Flow.csv` with 84 columns.

The output includes identifiers and the placeholder label `NeedManualLabel`. This program extracts features; it does not classify attacks or generate ground-truth labels.

## Requirements

- 64-bit JDK 8 for this legacy extractor
- Windows: WinPcap or Npcap installed with WinPcap API-compatible mode
- Linux: a compatible libpcap installation
- Internet access for the first Gradle dependency download

The web application uses a separate JDK 21+ toolchain. In the extractor's PowerShell terminal, select JDK 8 explicitly:

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk8'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
```

The bundled jNetPcap files contain platform-specific native libraries and their original license notices.

## Build and run on Windows

Run from the `pcap-extractor` directory:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\pcap2csv\build.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\pcap2csv\run.ps1 -InputPath 'C:\captures\sample.pcap' -OutputDirectory 'C:\captures\csv'
```

The build checks hashes of the pinned upstream sources, compiles the CLI, runs the existing compatibility checks, and creates a runnable distribution at `pcap2csv/build/install/pcap2csv`. The generated ZIP is under `pcap2csv/build/distributions`.

The installed launcher can also be used directly:

```powershell
.\pcap2csv\build\install\pcap2csv\bin\pcap2csv.bat 'C:\captures\sample.pcap' 'C:\captures\csv'
```

For a safe local example:

```powershell
python .\examples\generate_sample.py
powershell -NoProfile -ExecutionPolicy Bypass -File .\pcap2csv\run.ps1 -InputPath .\examples\sample.pcap -OutputDirectory .\examples\output
```

The example generator only writes a synthetic file. It does not send packets to a network.

## Compatibility

Feature calculations compile directly from the retained upstream source files, including `PacketReader`, `FlowGenerator`, `BasicFlow`, and `FlowFeature`. The SHA-256 manifest checks 16 pinned source files from upstream revision `98a5ebad0df579cc8b43eedd3421b3ae87699901`.

The actual enum and CSV output have 84 columns, even though an older upstream comment mentions 85 before a duplicate field was removed. The original formatting and calculation behavior are retained.

| Setting | Value |
| --- | --- |
| Bidirectional flows | Enabled |
| IPv4 | Enabled |
| IPv6 | Disabled |
| Flow timeout | 120 seconds |
| Activity threshold | 5 seconds |
| Default label | `NeedManualLabel` |

Directory inputs process recognized classic PCAP files at the first directory level. Subdirectories are not traversed. Offline extraction can overwrite an existing CSV with the same name, so choose a fresh output directory when keeping previous results.

PCAPNG is not treated as classic PCAP. This module does not include the original GUI, live adapter capture, charting, or Weka functionality. The additional sources used by the compatibility checks are retained only to compare against the original entry point.

## Follow mode

The CLI can follow an append-only PCAP file and periodically replace a complete flow snapshot. See [FOLLOW.md](FOLLOW.md) for commands and limitations.

## Attribution

See [the retained CICFlowMeter license](../LICENSE.txt) and the repository's [third-party notices](../../THIRD_PARTY_NOTICES.md).
