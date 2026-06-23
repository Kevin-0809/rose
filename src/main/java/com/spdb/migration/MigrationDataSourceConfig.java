package com.spdb.migration;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;

@Configuration
public class MigrationDataSourceConfig {
    static final String SOURCE_LABEL = "bxds";

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties primaryDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource dataSource(@Qualifier("primaryDataSourceProperties") DataSourceProperties properties,
                                       @Value("${spring.jpa.properties.hibernate.default_schema:tss}") String targetSchema) {
        HikariDataSource dataSource = properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
        dataSource.setSchema(targetSchema(targetSchema));
        return dataSource;
    }

    @Bean
    @Primary
    public NamedParameterJdbcTemplate namedParameterJdbcTemplate(@Qualifier("dataSource") DataSource dataSource) {
        return new NamedParameterJdbcTemplate(dataSource);
    }

    @Bean
    @ConfigurationProperties("rose.datasource.bxds")
    public DataSourceProperties bxdsDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @ConfigurationProperties("rose.datasource.bxds.hikari")
    public HikariDataSource bxdsDataSource(@Qualifier("bxdsDataSourceProperties") DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    @Bean
    public NamedParameterJdbcTemplate bxdsJdbcTemplate(@Qualifier("bxdsDataSource") DataSource dataSource) {
        return new NamedParameterJdbcTemplate(dataSource);
    }

    @Bean
    public MigrationRuntimeProperties migrationRuntimeProperties(
            @Value("${spring.jpa.properties.hibernate.default_schema:tss}") String targetSchema) {
        return new MigrationRuntimeProperties(sourceLabel(), targetSchema(targetSchema));
    }

    String sourceLabel() {
        return SOURCE_LABEL;
    }

    String targetSchema(String targetSchema) {
        return StringUtils.hasText(targetSchema) ? targetSchema.trim() : "tss";
    }
}
