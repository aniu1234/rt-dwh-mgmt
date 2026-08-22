package com.rtdwh.repository;

import com.rtdwh.entity.QueryHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface QueryHistoryRepository extends JpaRepository<QueryHistory, Long> {

    List<QueryHistory> findByUserIdOrderByCreatedAtDesc(Long userId);

    List<QueryHistory> findTop1000ByUserIdOrderByCreatedAtDesc(Long userId);

    Page<QueryHistory> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    List<QueryHistory> findByStatusOrderByCreatedAtDesc(QueryHistory.QueryStatus status);
}
