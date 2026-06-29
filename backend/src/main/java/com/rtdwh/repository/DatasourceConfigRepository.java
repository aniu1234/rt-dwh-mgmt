package com.rtdwh.repository;

import com.rtdwh.entity.DatasourceConfig;
import com.rtdwh.entity.DatasourceConfig.DbType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DatasourceConfigRepository extends JpaRepository<DatasourceConfig, Long> {

    List<DatasourceConfig> findByDbType(DbType dbType);

    List<DatasourceConfig> findByCreatorId(Long creatorId);
}
