package com.scx.review;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface TrafficRepository extends JpaRepository<TrafficRecord, Long> {
    // The database row lock lasts for this short transaction; lockedUntil represents the editing lease.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from TrafficRecord r where r.id = :id and r.deletedAt is null")
    Optional<TrafficRecord> findForWrite(@Param("id") Long id);

    @Modifying
    @Query("update TrafficRecord r set r.lockedBy = null, r.lockToken = null, r.lockedUntil = null where r.lockedBy = :userId")
    void releaseUserLocks(@Param("userId") Long userId);
}
