package com.rtdwh.repository;

import com.rtdwh.entity.ReportTemplate;
import com.rtdwh.entity.ReportTemplate.ReportType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ReportTemplateRepository extends JpaRepository<ReportTemplate, Long> {

    List<ReportTemplate> findByCreatorId(Long creatorId);

    List<ReportTemplate> findByIsPublished(Boolean isPublished);

    List<ReportTemplate> findByReportType(ReportType reportType);
}
