package com.nexus.audit.query.infrastructure.mongodb;

import com.nexus.audit.query.application.model.ComplianceReport;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ComplianceReportRepository
        extends MongoRepository<ComplianceReport, String> {

    List<ComplianceReport> findByAuditorUserIdOrderByGeneratedAtDesc(
            String auditorUserId);

    List<ComplianceReport> findByReportStatus(String status);
}