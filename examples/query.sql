USE pcap_features;

SELECT id, source_file, row_count, imported_at FROM pcap_imports;

SELECT flow_index, src_ip, src_port, dst_ip, dst_port,
       protocol, flow_duration, label, JSON_LENGTH(features) AS csv_columns
FROM traffic_flows
ORDER BY import_id, flow_index;

-- All original column names and values are retained in the JSON object.
SELECT features->>'$."Total Fwd Packet"' AS forward_packets,
       features->>'$."Flow Packets/s"' AS packets_per_second
FROM traffic_flows
WHERE src_ip = '192.0.2.10';
