package com.spdb.repository;

import com.spdb.domain.FieldMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface FieldMappingRepository extends JpaRepository<FieldMapping, Long>, JpaSpecificationExecutor<FieldMapping> {
}
