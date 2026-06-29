package com.rtdwh.repository;

import com.rtdwh.entity.TableMaintenanceLog;
import com.rtdwh.entity.TableMaintenanceLog.Operation;
import com.rtdwh.entity.TableMaintenanceLog.Status;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TableMaintenanceLogRepository extends JpaRepository<TableMaintenanceLog, Long> {

    List<TableMaintenanceLog> findByTableMetaIdOrderByStartedAtDesc(Long tableMetaId);

    List<TableMaintenanceLog> findByOperationOrderByStartedAtDesc(Operation operation);

    List<TableMaintenanceLog> findByStatus(Status status);

    @Query("SELECT m FROM TableMaintenanceLog m WHERE " +
           "(:operation IS NULL OR m.operation = :operation) AND " +
           "(:status IS NULL OR m.status = :status) AND " +
           "(:tableMetaId IS NULL OR m.tableMetaId = :tableMetaId) " +
           "ORDER BY m.startedAt DESC")
    List<TableMaintenanceLog> searchLogs(@Param("operation") Operation operation,
                                          @Param("status") Status status,
                                          @Param("tableMetaId") Long tableMetaId);
}
