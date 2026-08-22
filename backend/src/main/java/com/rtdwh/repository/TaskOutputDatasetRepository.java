package com.rtdwh.repository;

import com.rtdwh.entity.TaskOutputDataset;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TaskOutputDatasetRepository extends JpaRepository<TaskOutputDataset, Long> {
    List<TaskOutputDataset> findByTaskIdOrderById(Long taskId);
    List<TaskOutputDataset> findByTaskIdAndEnabledTrueOrderById(Long taskId);
    void deleteByTaskId(Long taskId);
}
