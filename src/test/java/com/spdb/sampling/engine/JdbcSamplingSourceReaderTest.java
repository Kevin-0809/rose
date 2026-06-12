package com.spdb.sampling.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcSamplingSourceReaderTest {
    private JdbcSamplingSourceReader reader;
    private DriverManagerDataSource dataSource;

    @BeforeEach
    void setUp() {
        dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:sampling_reader;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
        reader = new JdbcSamplingSourceReader(dataSource, 2);
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        jdbc.execute("drop table if exists tss_field_comp");
        jdbc.execute("drop table if exists tss_retcode_comp");
        jdbc.execute("drop table if exists tss_tran_comp");
        jdbc.execute("""
                create table tss_tran_comp (
                    mesg_seq varchar(64),
                    orig_cdate varchar(8),
                    conv_index integer,
                    conv_cindex integer,
                    comp_date varchar(8),
                    dest_trcd varchar(200),
                    orig_tran_res varchar(32),
                    dest_tran_res varchar(32),
                    comp_result varchar(1)
                )
                """);
        jdbc.execute("""
                create table tss_field_comp (
                    mesg_seq varchar(64),
                    orig_cdate varchar(8),
                    dest_trcd varchar(200),
                    conv_index integer,
                    conv_cindex integer,
                    redo_index integer,
                    field_index integer,
                    field_file_flag varchar(32),
                    orig_field_name varchar(200),
                    orig_field_value varchar(2000),
                    dest_field_name varchar(200),
                    dest_field_value varchar(2000),
                    comp_result varchar(1)
                )
                """);
        jdbc.execute("""
                create table tss_retcode_comp (
                    mesg_seq varchar(64),
                    service_code varchar(200),
                    orig_cdate varchar(8),
                    orig_error_code varchar(64),
                    orig_error_desc varchar(500),
                    dest_error_code varchar(64),
                    dest_error_desc varchar(500)
                )
                """);
    }

    @Test
    void sourceQueriesDoNotUseOffsetPagination() {
        assertThat(JdbcSamplingSourceReader.TRAN_FACT_SQL.toLowerCase()).doesNotContain(" offset ");
        assertThat(JdbcSamplingSourceReader.FIELD_DIFF_SQL.toLowerCase()).doesNotContain(" offset ");
        assertThat(JdbcSamplingSourceReader.RETURN_CODE_SQL.toLowerCase()).doesNotContain(" offset ");
        assertThat(JdbcSamplingSourceReader.TRAN_FACT_SQL.toLowerCase()).doesNotContain(" limit ");
        assertThat(JdbcSamplingSourceReader.FIELD_DIFF_SQL.toLowerCase()).doesNotContain(" limit ");
        assertThat(JdbcSamplingSourceReader.RETURN_CODE_SQL.toLowerCase()).doesNotContain(" limit ");
    }

    @Test
    void readsTranFactsInStableOrder() {
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        jdbc.update("""
                insert into tss_tran_comp
                (mesg_seq, orig_cdate, conv_index, conv_cindex, comp_date, dest_trcd, orig_tran_res, dest_tran_res, comp_result)
                values
                ('B', '20260611', 1, 1, '20260611', 'Svc&sop', '2', '2', '4'),
                ('A', '20260611', 1, 1, '20260611', 'Svc&bizjson', '2', '2', '1')
                """);
        List<TranFact> facts = new ArrayList<>();

        reader.readTranFacts("20260611", facts::add);

        assertThat(facts).extracting(fact -> fact.sourceKey().mesgSeq()).containsExactly("A", "B");
        assertThat(facts).extracting(TranFact::serviceCode).containsExactly("Svc", "Svc");
        assertThat(facts).extracting(TranFact::messageType).containsExactly("bizjson", "sop");
    }

    @Test
    void readsFieldDiffsInStableOrder() {
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        jdbc.update("""
                insert into tss_field_comp
                (mesg_seq, orig_cdate, dest_trcd, conv_index, conv_cindex, redo_index, field_index,
                 field_file_flag, orig_field_name, orig_field_value, dest_field_name, dest_field_value, comp_result)
                values
                ('A', '20260611', 'Svc&sop', 1, 1, null, 2, null, 'Second', '2', 'Second', '3', '0'),
                ('A', '20260611', 'Svc&sop', 1, 1, null, 1, null, 'First', '1', 'First', '2', '0')
                """);
        List<FieldDiff> diffs = new ArrayList<>();

        reader.readFieldDiffs("20260611", diffs::add);

        assertThat(diffs).extracting(FieldDiff::rawFieldName).containsExactly("First", "Second");
    }
}
