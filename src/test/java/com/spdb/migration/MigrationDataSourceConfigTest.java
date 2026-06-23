package com.spdb.migration;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationDataSourceConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    DataSourceAutoConfiguration.class,
                    JdbcTemplateAutoConfiguration.class
            ))
            .withUserConfiguration(MigrationDataSourceConfig.class)
            .withPropertyValues(
                    "spring.datasource.url=jdbc:h2:mem:primary;DB_CLOSE_DELAY=-1",
                    "spring.datasource.username=sa",
                    "spring.datasource.password=",
                    "spring.datasource.driver-class-name=org.h2.Driver",
                    "rose.datasource.bxds.url=jdbc:h2:mem:bxds;DB_CLOSE_DELAY=-1",
                    "rose.datasource.bxds.username=sa",
                    "rose.datasource.bxds.password=",
                    "rose.datasource.bxds.driver-class-name=org.h2.Driver"
            );

    @Test
    void targetSchemaDefaultsToTssWhenBlank() {
        MigrationDataSourceConfig config = new MigrationDataSourceConfig();

        assertThat(config.targetSchema(null)).isEqualTo("tss");
        assertThat(config.targetSchema("")).isEqualTo("tss");
        assertThat(config.targetSchema(" main_schema ")).isEqualTo("main_schema");
    }

    @Test
    void sourceLabelIsBxds() {
        MigrationDataSourceConfig config = new MigrationDataSourceConfig();

        assertThat(config.sourceLabel()).isEqualTo("bxds");
    }

    @Test
    void unqualifiedJdbcTemplateUsesPrimaryDatasourceAndBxdsIsQualified() {
        contextRunner.run(context -> {
            assertThat(context).hasBean("bxdsJdbcTemplate");
            assertThat(context).hasBean("namedParameterJdbcTemplate");

            DataSource primaryDataSource = context.getBean(DataSource.class);
            DataSource bxdsDataSource = context.getBean("bxdsDataSource", DataSource.class);
            NamedParameterJdbcTemplate primaryJdbcTemplate = context.getBean(NamedParameterJdbcTemplate.class);
            NamedParameterJdbcTemplate namedPrimaryJdbcTemplate =
                    context.getBean("namedParameterJdbcTemplate", NamedParameterJdbcTemplate.class);
            NamedParameterJdbcTemplate bxdsJdbcTemplate = context.getBean("bxdsJdbcTemplate", NamedParameterJdbcTemplate.class);

            assertThat(primaryJdbcTemplate).isSameAs(namedPrimaryJdbcTemplate);
            assertThat(primaryDataSource).isNotSameAs(bxdsDataSource);
            assertThat(jdbcDataSource(primaryJdbcTemplate)).isSameAs(primaryDataSource);
            assertThat(jdbcDataSource(bxdsJdbcTemplate)).isSameAs(bxdsDataSource);
            assertThat(((HikariDataSource) primaryDataSource).getJdbcUrl()).contains("primary");
            assertThat(((HikariDataSource) bxdsDataSource).getJdbcUrl()).contains("bxds");
        });
    }

    private DataSource jdbcDataSource(NamedParameterJdbcTemplate jdbcTemplate) {
        return jdbcTemplate.getJdbcTemplate().getDataSource();
    }
}
