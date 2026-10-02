# Third-party notices

## CICFlowMeter

The extractor reuses selected sources from CICFlowMeter V4, upstream revision `98a5ebad0df579cc8b43eedd3421b3ae87699901`.

- Upstream project: https://github.com/ahlashkari/CICFlowMeter
- Retained license and citation information: [pcap-extractor/LICENSE.txt](pcap-extractor/LICENSE.txt)
- Pinned source hashes: [upstream-sources.sha256](pcap-extractor/pcap2csv/upstream-sources.sha256)

The feature calculation classes are preserved byte for byte. The standalone command-line entry point and append-only file-following adapter reuse those classes.

## jNetPcap

The native Java packet reader is bundled with the original distribution's license files and release notes:

- [Windows license](pcap-extractor/jnetpcap/win/jnetpcap-1.4.r1425/COPYING)
- [Windows LGPL terms](pcap-extractor/jnetpcap/win/jnetpcap-1.4.r1425/COPYING.LESSER)
- [Linux license](pcap-extractor/jnetpcap/linux/jnetpcap-1.4.r1425/COPYING)
- [Linux LGPL terms](pcap-extractor/jnetpcap/linux/jnetpcap-1.4.r1425/COPYING.LESSER)

## Other dependencies

Java, JavaScript, and Python dependencies are declared in `build.gradle`, `pom.xml`, `package.json`, `package-lock.json`, and `model/requirements.txt`. They retain their respective licenses. Generated dependency caches and installed runtimes are not repository source files.

## Data

The web sample's exact source filename, source hash, sampling method, seed, and class counts are recorded in [sample-info.json](review-webapp/data/sample-info.json). The PCAP example is generated locally from synthetic packets; it does not contain captured user traffic.
