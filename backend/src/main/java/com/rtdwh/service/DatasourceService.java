package com.rtdwh.service;

import com.rtdwh.entity.DatasourceConfig;
import com.rtdwh.repository.DatasourceConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DatasourceService {

    private final DatasourceConfigRepository datasourceConfigRepository;

    @Transactional(readOnly = true)
    public List<DatasourceConfig> listDatasources() {
        return datasourceConfigRepository.findAll();
    }

    @Transactional(readOnly = true)
    public DatasourceConfig getDatasource(Long id) {
        return datasourceConfigRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("数据源不存在: " + id));
    }

    @Transactional
    public DatasourceConfig createDatasource(DatasourceConfig config, Long creatorId) {
        config.setId(null);
        config.setCreatorId(creatorId);
        return datasourceConfigRepository.save(config);
    }

    @Transactional
    public DatasourceConfig updateDatasource(Long id, DatasourceConfig config) {
        DatasourceConfig existing = getDatasource(id);
        config.setId(existing.getId());
        return datasourceConfigRepository.save(config);
    }

    @Transactional
    public void deleteDatasource(Long id) {
        getDatasource(id);
        datasourceConfigRepository.deleteById(id);
    }
}
