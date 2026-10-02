# Third-party notices

## CICFlowMeter

Feature extraction reuses ten unmodified Java sources from [CICFlowMeter](https://github.com/ahlashkari/CICFlowMeter), revision `98a5ebad0df579cc8b43eedd3421b3ae87699901`.

`vendor/cicflowmeter-core.zip` contains these sources and the original MIT notice in `LICENSE-CICFlowMeter.txt`. Its `SHA256SUMS.json` records each retained file. The application wrapper controls output and database storage; it does not reimplement CICFlowMeter feature calculations.

## jNetPcap

The same vendor archive includes jNetPcap `1.4.r1425` for Windows x64. Its `jnetpcap/COPYING`, `jnetpcap/COPYING.LESSER`, and `jnetpcap/RELEASE_NOTES.txt` are retained. jNetPcap is licensed under the GNU LGPL with the accompanying GPL text. The original project is available at [jNetPcap](https://sourceforge.net/projects/jnetpcap/).

## Downloaded Java dependencies

`build.ps1` downloads these unmodified JARs from Maven Central to an ignored local directory and verifies SHA-256 digests. Each JAR retains the publisher's embedded license notices.

| Library | Version | License |
| --- | --- | --- |
| SLF4J API and NOP binding | 1.7.25 | MIT |
| Apache Commons Lang | 3.6 | Apache-2.0 |
| Apache Commons Math | 3.5 | Apache-2.0 |
| Apache Tika Core | 1.17 | Apache-2.0 |
| MySQL Connector/J | 9.7.0 | GPL-2.0 with the publisher's Universal FOSS Exception; see embedded notices |

The packet-capture driver and MySQL server are installed separately. Their licenses apply independently.
