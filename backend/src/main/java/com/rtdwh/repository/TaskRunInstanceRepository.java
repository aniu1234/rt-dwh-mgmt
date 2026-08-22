package com.rtdwh.repository;

import com.rtdwh.entity.TaskRunInstance;
import com.rtdwh.entity.TaskRunInstance.RunStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface TaskRunInstanceRepository extends JpaRepository<TaskRunInstance, Long> {
    List<TaskRunInstance> findByStatusOrderByCreatedAtAsc(RunStatus status, Pageable pageable);
    List<TaskRunInstance> findByTaskIdOrderByCreatedAtDesc(Long taskId, Pageable pageable);
    List<TaskRunInstance> findByTaskIdAndStatusOrderByCreatedAtDesc(Long taskId, RunStatus status, Pageable pageable);
    Optional<TaskRunInstance> findFirstByTaskIdAndBusinessDateAndStatusOrderByCreatedAtDesc(
            Long taskId, LocalDate businessDate, RunStatus status);
    List<TaskRunInstance> findByStatusAndExecutorIdOrderByUpdatedAtAsc(
            RunStatus status, String executorId, Pageable pageable);
    List<TaskRunInstance> findByStatusAndLeaseExpiresAtBeforeOrderByLeaseExpiresAtAsc(
            RunStatus status, LocalDateTime leaseExpiresAt, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select instance from TaskRunInstance instance "
            + "where instance.status = :status "
            + "and (instance.nextRetryAt is null or instance.nextRetryAt <= :now) "
            + "order by instance.createdAt asc")
    List<TaskRunInstance> findRunnableForUpdate(@Param("status") RunStatus status,
                                                @Param("now") LocalDateTime now,
                                                Pageable pageable);
}
