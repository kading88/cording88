"""Write a small, deterministic classic PCAP with synthetic TCP and UDP flows."""
from pathlib import Path
import socket
import struct

def checksum(data):
    if len(data) % 2:
        data += b'\0'
    value = sum(struct.unpack('!' + 'H' * (len(data) // 2), data))
    while value >> 16:
        value = (value & 0xffff) + (value >> 16)
    return (~value) & 0xffff

def packet(protocol, source, target, source_port, target_port, payload, identifier):
    src, dst = socket.inet_aton(source), socket.inet_aton(target)
    if protocol == 17:
        transport = struct.pack('!HHHH', source_port, target_port, 8 + len(payload), 0) + payload
    else:
        header = struct.pack('!HHLLBBHHH', source_port, target_port, identifier * 100, 1, 5 << 4, 0x18, 8192, 0, 0)
        pseudo = src + dst + struct.pack('!BBH', 0, protocol, len(header) + len(payload))
        value = checksum(pseudo + header + payload)
        transport = header[:16] + struct.pack('!H', value) + header[18:] + payload
    ip = struct.pack('!BBHHHBBH4s4s', 0x45, 0, 20 + len(transport), identifier, 0, 64, protocol, 0, src, dst)
    ip = ip[:10] + struct.pack('!H', checksum(ip)) + ip[12:]
    ethernet = bytes.fromhex('0200000000020200000000010800')
    return ethernet + ip + transport

def main():
    output = Path(__file__).with_name('sample.pcap')
    records = []
    for index in range(4):
        reverse = index % 2 == 1
        source, target = ('198.51.100.20', '192.0.2.10') if reverse else ('192.0.2.10', '198.51.100.20')
        ports = (443, 41000) if reverse else (41000, 443)
        records.append((index * 120000, packet(6, source, target, *ports, b'tcp-demo-' + bytes([65 + index]) * (12 + index), index + 1)))
        ports = (53, 53000) if reverse else (53000, 53)
        records.append((index * 120000 + 20000, packet(17, source, target, *ports, b'udp-demo-' + bytes([65 + index]) * (5 + index), index + 5)))
    with output.open('wb') as stream:
        stream.write(struct.pack('<IHHIIII', 0xa1b2c3d4, 2, 4, 0, 0, 65535, 1))
        for microseconds, frame in sorted(records):
            stream.write(struct.pack('<IIII', 1704067200, microseconds, len(frame), len(frame)))
            stream.write(frame)
    print(f'Wrote {len(records)} synthetic packets to {output.name}')

if __name__ == '__main__':
    main()
