package com.scx.review;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "traffic_records")
public class TrafficRecord {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(name = "source_key", length = 96, nullable = false) public String sourceKey;
    @Column(name = "source_row", nullable = false) public int sourceRow;
    @Column(name = "model_version", length = 64, nullable = false) public String modelVersion;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "features_json", columnDefinition = "json", nullable = false)
    public Map<String, Double> features;
    @Column(name = "predicted_label", length = 16, nullable = false) public String predictedLabel;
    public double score;
    @Column(name = "quantization_error") public double quantizationError;
    @Column(name = "som_x") public int somX;
    @Column(name = "som_y") public int somY;
    @Column(name = "neuron_count") public int neuronCount;
    @Column(name = "cluster_purity") public double clusterPurity;
    @Column(name = "reviewed_label", length = 16) public String reviewedLabel;
    @Column(length = 1000) public String note;
    @Column(name = "reviewed_by") public Long reviewedBy;
    @Column(name = "updated_at") public Instant updatedAt;
    @Column(name = "created_at") public Instant createdAt;
    @Column(name = "deleted_at") public Instant deletedAt;
    @Column(name = "locked_by") public Long lockedBy;
    @Column(name = "lock_token", length = 36) public String lockToken;
    @Column(name = "locked_until") public Instant lockedUntil;

    public void unlock() { lockedBy = null; lockToken = null; lockedUntil = null; }
}
