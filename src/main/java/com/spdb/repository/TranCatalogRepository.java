package com.spdb.repository;

import com.spdb.domain.TranCatalog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface TranCatalogRepository extends JpaRepository<TranCatalog, Long>, JpaSpecificationExecutor<TranCatalog> {
}
