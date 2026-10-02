package com.scx.review;

import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecordService {
    private final TrafficRepository records;
    private final UserRepository users;
    private final JdbcTemplate jdbc;
    private final long lockSeconds;
    public RecordService(TrafficRepository records, UserRepository users, JdbcTemplate jdbc,
            @Value("${app.lock-seconds:300}") long lockSeconds) {
        this.records = records; this.users = users; this.jdbc = jdbc; this.lockSeconds = lockSeconds;
    }

    public Map<String, Object> list(int page, int size, String label, String status, String order) {
        if (page < 1 || page > 100000 || size < 1 || size > 100) throw new IllegalArgumentException();
        if (!Set.of("", "NORMAL", "ATTACK").contains(label) || !Set.of("", "PENDING", "REVIEWED").contains(status)
                || !Set.of("score", "id").contains(order)) throw new IllegalArgumentException();
        String where = " WHERE r.deleted_at IS NULL";
        List<Object> args = new ArrayList<>();
        if (!label.isEmpty()) { where += " AND r.predicted_label = ?"; args.add(label); }
        if (status.equals("PENDING")) where += " AND r.reviewed_label IS NULL";
        if (status.equals("REVIEWED")) where += " AND r.reviewed_label IS NOT NULL";
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM traffic_records r" + where, Long.class, args.toArray());
        String sql = "SELECT r.id, r.source_row, r.predicted_label, r.score, r.reviewed_label, r.updated_at, "
            + "u.username reviewer, CASE WHEN r.locked_until > UTC_TIMESTAMP(6) THEN lu.username ELSE NULL END lock_owner "
            + "FROM traffic_records r LEFT JOIN users u ON r.reviewed_by=u.id LEFT JOIN users lu ON r.locked_by=lu.id"
            + where + (order.equals("score") ? " ORDER BY r.score ASC, r.id ASC" : " ORDER BY r.id ASC") + " LIMIT ? OFFSET ?";
        args.add(size); args.add((page - 1) * size);
        var rows = jdbc.query(sql, (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getLong("id")); row.put("sourceRow", rs.getInt("source_row"));
            row.put("predictedLabel", rs.getString("predicted_label")); row.put("score", rs.getDouble("score"));
            row.put("reviewedLabel", rs.getString("reviewed_label")); row.put("reviewedBy", rs.getString("reviewer"));
            var timestamp = rs.getTimestamp("updated_at");
            row.put("updatedAt", timestamp == null ? null : timestamp.toLocalDateTime().toInstant(java.time.ZoneOffset.UTC));
            row.put("lockedBy", rs.getString("lock_owner")); return row;
        }, args.toArray());
        return Map.of("items", rows, "total", total, "page", page, "size", size);
    }

    public Map<String, Object> stats() {
        return jdbc.queryForMap("SELECT COUNT(*) total, COALESCE(SUM(reviewed_label IS NULL),0) pending, "
            + "COALESCE(SUM(reviewed_label IS NOT NULL),0) reviewed, "
            + "COALESCE(SUM(reviewed_label IS NOT NULL AND reviewed_label <> predicted_label),0) corrected "
            + "FROM traffic_records WHERE deleted_at IS NULL");
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(long id) {
        TrafficRecord r = records.findById(id).filter(x -> x.deletedAt == null)
            .orElseThrow(() -> new ApiError(HttpStatus.NOT_FOUND, "NOT_FOUND", "The record does not exist or has been deleted."));
        return view(r);
    }

    @Transactional
    public Map<String, Object> acquire(long id, String username) {
        UserAccount u = user(username);
        TrafficRecord r = writable(id);
        Instant now = Instant.now();
        if (r.lockedUntil != null && r.lockedUntil.isAfter(now)) {
            String owner = users.findById(r.lockedBy).map(x -> x.username).orElse("another user");
            throw new ApiError(HttpStatus.LOCKED, "LOCKED", "This record is being edited by " + owner + ". Please try again later.");
        }
        r.lockedBy = u.id; r.lockToken = UUID.randomUUID().toString(); r.lockedUntil = now.plusSeconds(lockSeconds);
        // Return the current row after acquiring its lock instead of editing stale browser data.
        return Map.of("record", view(r), "lockToken", r.lockToken, "expiresAt", r.lockedUntil);
    }

    @Transactional
    public Map<String, Object> save(long id, String username, String token, String label, String note) {
        UserAccount u = user(username); TrafficRecord r = writable(id); requireLease(r, u.id, token);
        if (!Set.of("NORMAL", "ATTACK").contains(label) || note == null || note.length() > 1000) throw new IllegalArgumentException();
        if (!label.equals(r.predictedLabel) && note.isBlank())
            throw new ApiError(HttpStatus.BAD_REQUEST, "REASON_REQUIRED", "A review reason is required when changing the model label.");
        r.reviewedLabel = label; r.note = note.strip(); r.reviewedBy = u.id; r.updatedAt = Instant.now(); r.unlock();
        return view(r);
    }

    @Transactional
    public void release(long id, String username, String token) {
        UserAccount u = user(username);
        records.findForWrite(id).ifPresent(r -> {
            // A stale cancellation must not release a lease acquired by a newer editor.
            if (Objects.equals(r.lockedBy, u.id) && Objects.equals(r.lockToken, token)) r.unlock();
        });
    }

    @Transactional
    public void delete(long id, String username, String token) {
        UserAccount u = user(username);
        if (!"ADMIN".equals(u.role)) throw new ApiError(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only administrators can delete records.");
        TrafficRecord r = writable(id); requireLease(r, u.id, token);
        r.deletedAt = Instant.now(); r.updatedAt = r.deletedAt; r.reviewedBy = u.id; r.unlock();
    }

    @Transactional
    public void releaseAll(String username) {
        users.findByUsername(username).ifPresent(u -> records.releaseUserLocks(u.id));
    }

    private TrafficRecord writable(long id) {
        return records.findForWrite(id).orElseThrow(() -> new ApiError(HttpStatus.NOT_FOUND, "NOT_FOUND", "The record does not exist or has been deleted."));
    }
    private UserAccount user(String name) { return users.findByUsername(name).orElseThrow(); }
    private void requireLease(TrafficRecord r, Long userId, String token) {
        if (token == null || !Objects.equals(r.lockedBy, userId) || !Objects.equals(r.lockToken, token)
                || r.lockedUntil == null || !r.lockedUntil.isAfter(Instant.now()))
            throw new ApiError(HttpStatus.CONFLICT, "LOCK_EXPIRED", "The edit lease has expired. Acquire a new lease and check the latest record.");
    }
    private Map<String, Object> view(TrafficRecord r) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", r.id); v.put("sourceRow", r.sourceRow); v.put("modelVersion", r.modelVersion);
        v.put("features", r.features); v.put("predictedLabel", r.predictedLabel); v.put("score", r.score);
        v.put("quantizationError", r.quantizationError); v.put("somX", r.somX); v.put("somY", r.somY);
        v.put("neuronCount", r.neuronCount); v.put("clusterPurity", r.clusterPurity);
        v.put("reviewedLabel", r.reviewedLabel); v.put("note", r.note); v.put("updatedAt", r.updatedAt);
        v.put("reviewedBy", r.reviewedBy == null ? null : users.findById(r.reviewedBy).map(x -> x.username).orElse(null));
        v.put("lockedBy", r.lockedUntil != null && r.lockedUntil.isAfter(Instant.now())
            ? users.findById(r.lockedBy).map(x -> x.username).orElse(null) : null);
        return v;
    }
}
