package com.rtdwh.repository;

import com.rtdwh.entity.TaskRunInstance;
import com.rtdwh.entity.TaskRunInstance.RunStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface TaskRunInstanceRepository extends JpaRepository<TaskRunInstance, Long> {
    List<TaskRunInstance> findByStatusOrderByCreatedAtAsc(RunStatus status, Pageable pageable);
    List<TaskRunInstance> findByTaskIdOrderByCreatedAtDesc(Long taskId, Pageable pageable);
    List<TaskRunInstance> findByTaskIdAndStatusOrderByCreatedAtDesc(Long taskId, RunStatus status, Pageable pageable);
    Optional<TaskRunInstance> findFirstByTaskIdAndBusinessDateAndStatusOrderByCreatedAtDesc(
            Long taskId, LocalDate businessDate, RunStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<TaskRunInstance> findFirstByStatusOrderByCreatedAtAsc(RunStatus status);
}
