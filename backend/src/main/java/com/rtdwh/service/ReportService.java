package com.rtdwh.service;

import com.rtdwh.entity.ReportTemplate;
import com.rtdwh.repository.ReportTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReportService {

    private final ReportTemplateRepository reportTemplateRepository;

    @Transactional(readOnly = true)
    public List<ReportTemplate> listReports() {
        return reportTemplateRepository.findAll();
    }

    @Transactional(readOnly = true)
    public ReportTemplate getReport(Long id) {
        return reportTemplateRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("报表不存在: " + id));
    }

    @Transactional
    public ReportTemplate createReport(ReportTemplate template, Long creatorId) {
        template.setId(null);
        LocalDateTime now = LocalDateTime.now();
        template.setCreatedAt(now);
        template.setUpdatedAt(now);
        template.setIsPublished(false);
        template.setCreatorId(creatorId);
        return reportTemplateRepository.save(template);
    }

    @Transactional
    public ReportTemplate updateReport(Long id, ReportTemplate template) {
        ReportTemplate existing = reportTemplateRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("报表不存在: " + id));
        template.setId(existing.getId());
        template.setCreatedAt(existing.getCreatedAt());
        template.setUpdatedAt(LocalDateTime.now());
        template.setCreatorId(existing.getCreatorId());
        return reportTemplateRepository.save(template);
    }

    @Transactional
    public void deleteReport(Long id) {
        reportTemplateRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("报表不存在: " + id));
        reportTemplateRepository.deleteById(id);
    }
}
