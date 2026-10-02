# Third-party notices

## CICFlowMeter

Feature extraction reuses ten unmodified Java sources from [CICFlowMeter](https://github.com/ahlashkari/CICFlowMeter), revision `98a5ebad0df579cc8b43eedd3421b3ae87699901`.

The sources are placed directly in `src/`, alongside `PcapToCsv.java` and `MySqlStore.java`. Their package declarations and source bytes are unchanged. The original MIT notice is retained in `LICENSE-CICFlowMeter.txt`. The application wrapper controls output and database storage; it does not reimplement CICFlowMeter feature calculations.

## jNetPcap

`lib/jnetpcap/` contains jNetPcap `1.4.r1425` for Windows x64 as individual JAR and DLL files. Its `COPYING`, `COPYING.LESSER`, and `RELEASE_NOTES.txt` are retained in that directory. jNetPcap is licensed under the GNU LGPL with the accompanying GPL text. The original project is available at [jNetPcap](https://sourceforge.net/projects/jnetpcap/).

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

## SCX Python dependencies

`scx/requirements.txt` lists NumPy, pandas, Matplotlib, MiniSom, scikit-learn, XGBoost, and psutil. Install these packages separately with pip. Their upstream licenses remain applicable. No Python package binaries, trained model files, or research datasets are included in this repository.
