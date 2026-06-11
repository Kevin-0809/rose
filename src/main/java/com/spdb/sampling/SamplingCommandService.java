package com.spdb.sampling;

import com.spdb.web.PageRequestParams;
import com.spdb.web.PagedResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Service
public class SamplingCommandService {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HHmmss");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectProvider<SamplingTaskLauncher> samplingTaskLauncher;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    @Autowired
    public SamplingCommandService(NamedParameterJdbcTemplate jdbc,
                                  ObjectProvider<SamplingTaskLauncher> samplingTaskLauncher,
                                  PlatformTransactionManager transactionManager) {
        this(jdbc, samplingTaskLauncher, transactionManager, Clock.systemDefaultZone());
    }

    SamplingCommandService(NamedParameterJdbcTemplate jdbc, ObjectProvider<SamplingTaskLauncher> samplingTaskLauncher, Clock clock) {
        this(jdbc, samplingTaskLauncher, null, clock);
    }

    SamplingCommandService(NamedParameterJdbcTemplate jdbc,
                           ObjectProvider<SamplingTaskLauncher> samplingTaskLauncher,
                           PlatformTransactionManager transactionManager,
                           Clock clock) {
        this.jdbc = jdbc;
        this.samplingTaskLauncher = samplingTaskLauncher;
        this.transactionTemplate = transactionManager == null ? null : new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public String nextBatchId(String origCdate) {
        LocalDateTime now = LocalDateTime.now(clock);
        int suffix = RANDOM.nextInt(10_000);
        return "SMP" + normalizeOrigCdate(origCdate) + "-" + TIME.format(now) + "-" + String.format("%04d", suffix);
    }

    public String createCommand(SamplingCommandForm form) {
        validate(form);
        String origCdate = normalizeOrigCdate(form.origCdate());
        String batchId = nextBatchId(origCdate);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("batchId", batchId)
                .addValue("origCdate", origCdate)
                .addValue("remark", textOrNull(form.remark()));
        jdbc.update("""
                insert into ana_sampling_command (
                    batch_id, orig_cdate, status, remark, created_by
                ) values (
                    :batchId, :origCdate, 'CREATED', :remark, '系统'
                )
                """, params);
        if (samplingTaskLauncher != null) {
            SamplingTaskLauncher launcher = samplingTaskLauncher.getIfAvailable();
            if (launcher != null) {
                launcher.launch(batchId);
            }
        }
        return batchId;
    }

    public PagedResult<SamplingCommandRow> search(SamplingCommandSearchCriteria criteria, PageRequestParams page) {
        QueryParts query = where(criteria);
        query.params.addValue("limit", page.size()).addValue("offset", page.offset());
        List<SamplingCommandRow> rows = jdbc.query("""
                        select c.command_id, c.batch_id, c.orig_cdate, c.sample_type, c.tran_code, c.service_code,
                               c.status, c.job_execution_id,
                               case
                                   when c.started_time is null then null
                                   else extract(epoch from (coalesce(c.ended_time, current_timestamp) - c.started_time))::bigint
                               end as duration_seconds,
                               coalesce(s.total_tran_count, c.total_tran_count, 0) as total_tran_count,
                               coalesce(s.comp_result_1_count, 0) as comp_result_1_count,
                               coalesce(s.comp_result_2_count, 0) as comp_result_2_count,
                               coalesce(s.comp_result_3_count, 0) as comp_result_3_count,
                               coalesce(s.comp_result_4_count, 0) as comp_result_4_count,
                               coalesce(s.comp_result_8_count, 0) as comp_result_8_count,
                               coalesce(s.pass_tran_count, 0) as pass_tran_count,
                               coalesce(s.issue_field_count, c.field_diff_count, 0) as issue_field_count,
                               coalesce(s.fully_matched_count, 0) as fully_matched_count,
                               c.field_diff_count,
                               coalesce(s.sample_group_count, c.sample_group_count, 0) as sample_group_count,
                               coalesce(s.sample_detail_count, c.sample_detail_count, 0) as sample_detail_count,
                               c.error_message, c.remark,
                               created_time, started_time, ended_time
                        from ana_sampling_command c
                        left join ana_sampling_summary s on s.batch_id = c.batch_id
                        """ + query.where + " order by c.created_time desc limit :limit offset :offset",
                query.params, (rs, i) -> new SamplingCommandRow(
                        rs.getLong("command_id"),
                        rs.getString("batch_id"),
                        rs.getString("orig_cdate"),
                        rs.getString("sample_type"),
                        rs.getString("tran_code"),
                        rs.getString("service_code"),
                        rs.getString("status"),
                        getLongOrNull(rs.getObject("job_execution_id")),
                        formatDuration(getLongOrNull(rs.getObject("duration_seconds"))),
                        rs.getLong("total_tran_count"),
                        rs.getLong("comp_result_1_count"),
                        rs.getLong("comp_result_2_count"),
                        rs.getLong("comp_result_3_count"),
                        rs.getLong("comp_result_4_count"),
                        rs.getLong("comp_result_8_count"),
                        rs.getLong("pass_tran_count"),
                        rs.getLong("issue_field_count"),
                        rs.getLong("fully_matched_count"),
                        rs.getLong("field_diff_count"),
                        rs.getLong("sample_group_count"),
                        rs.getLong("sample_detail_count"),
                        rs.getString("error_message"),
                        rs.getString("remark"),
                        rs.getObject("created_time", LocalDateTime.class),
                        rs.getObject("started_time", LocalDateTime.class),
                        rs.getObject("ended_time", LocalDateTime.class)
                ));
        Long total = jdbc.queryForObject("select count(*) from ana_sampling_command c" + query.where, query.params, Long.class);
        return PagedResult.of(rows, total == null ? 0 : total, page);
    }

    public SamplingCommandRow findByBatchId(String batchId) {
        PagedResult<SamplingCommandRow> result = search(new SamplingCommandSearchCriteria(batchId, null, null), PageRequestParams.of(1, 20));
        return result.rows().isEmpty() ? null : result.rows().get(0);
    }

    public void markRunning(String batchId, Long jobExecutionId) {
        jdbc.update("""
                update ana_sampling_command
                   set status = 'RUNNING', job_execution_id = :jobExecutionId, started_time = current_timestamp
                 where batch_id = :batchId
                """, new MapSqlParameterSource().addValue("batchId", batchId).addValue("jobExecutionId", jobExecutionId));
    }

    @Transactional
    public void initializeSampling(String batchId) {
        SamplingCommandRow command = findByBatchId(batchId);
        if (command == null) {
            throw new IllegalArgumentException("采样批次不存在：" + batchId);
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("batchId", command.batchId())
                .addValue("origCdate", command.origCdate());

        jdbc.update("delete from ana_sample_detail where batch_id = :batchId", params);
        jdbc.update("delete from ana_sample_group where batch_id = :batchId", params);
        jdbc.update("delete from ana_sampling_candidate where batch_id = :batchId", params);
        jdbc.update("delete from ana_sampling_summary where batch_id = :batchId", params);
        jdbc.update("""
                insert into ana_sampling_summary (
                    batch_id, orig_cdate, total_tran_count,
                    comp_result_1_count, comp_result_2_count, comp_result_3_count,
                    comp_result_4_count, comp_result_8_count, pass_tran_count,
                    issue_field_count, fully_matched_count, sample_group_count, sample_detail_count
                ) values (
                    :batchId, :origCdate, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
                )
                """, params);
    }

    public void runSamplingBatch(String batchId) {
        initializeSampling(batchId);
        SamplingCommandRow command = findByBatchId(batchId);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("batchId", command.batchId())
                .addValue("origCdate", command.origCdate());

        materializeCandidates(params);

        jdbc.update(allCandidateGroupsCte() + """
                insert into ana_sample_group (
                    batch_id, sample_type, group_key, group_hash, dest_trcd, service_code, message_type, tran_code,
                    comp_result, sop_field_name, soap_field_name, bizjson_field_name, field_cn_name,
                    orig_field_value, dest_field_value, owner, affected_count, sample_count
                )
                select
                    :batchId, sample_type, group_key, group_hash, dest_trcd, service_code, message_type, tran_code,
                    comp_result, sop_field_name, soap_field_name, bizjson_field_name, field_cn_name,
                    orig_field_value, dest_field_value, owner, affected_count, least(affected_count, 100)
                from grouped
                """, params);
        jdbc.getJdbcTemplate().execute("analyze ana_sampling_candidate");
        jdbc.getJdbcTemplate().execute("analyze ana_sample_group");
        materializeDetailCandidates(params);

        Long total = jdbc.queryForObject("""
                select count(*)
                from tss_tran_comp t
                join (
                    select distinct service_code
                    from ana_tran_catalog
                ) c
                  on c.service_code = split_part(t.dest_trcd, '&', 1)
                where t.orig_cdate = :origCdate
                """, params, Long.class);
        Long comp1 = countTranByResult(params, "1");
        Long comp2 = countTranByResult(params, "2");
        Long comp3 = countTranByResult(params, "3");
        Long comp4 = countTranByResult(params, "4");
        Long comp8 = countTranByResult(params, "8");
        Long issueFieldCount = jdbc.queryForObject("""
                select count(*)
                from (
                    select distinct f.mesg_seq, f.conv_index, f.conv_cindex, f.orig_field_name
                    from tss_field_comp f
                    join tss_tran_comp t
                      on t.mesg_seq = f.mesg_seq
                     and t.conv_index = f.conv_index
                     and t.conv_cindex = f.conv_cindex
                    where t.orig_cdate = :origCdate
                      and f.orig_cdate = :origCdate
                      and f.comp_result = '0'
                ) d
                """, params, Long.class);
        Long fullyMatchedCount = jdbc.queryForObject("""
                select count(*)
                from tss_tran_comp t
                join (
                    select distinct service_code
                    from ana_tran_catalog
                ) c
                  on c.service_code = split_part(t.dest_trcd, '&', 1)
                where t.orig_cdate = :origCdate
                  and not exists (
                      select 1
                      from tss_field_comp f
                      where f.mesg_seq = t.mesg_seq
                        and f.conv_index = t.conv_index
                        and f.conv_cindex = t.conv_cindex
                        and f.comp_result = '0'
                  )
                """, params, Long.class);
        params.addValue("total", total == null ? 0L : total)
                .addValue("comp1", comp1 == null ? 0L : comp1)
                .addValue("comp2", comp2 == null ? 0L : comp2)
                .addValue("comp3", comp3 == null ? 0L : comp3)
                .addValue("comp4", comp4 == null ? 0L : comp4)
                .addValue("comp8", comp8 == null ? 0L : comp8)
                .addValue("issueFields", issueFieldCount == null ? 0L : issueFieldCount)
                .addValue("fullyMatched", fullyMatchedCount == null ? 0L : fullyMatchedCount);
        jdbc.update("""
                update ana_sampling_summary
                   set total_tran_count = :total,
                       comp_result_1_count = :comp1,
                       comp_result_2_count = :comp2,
                       comp_result_3_count = :comp3,
                       comp_result_4_count = :comp4,
                       comp_result_8_count = :comp8,
                       pass_tran_count = :comp4,
                       issue_field_count = :issueFields,
                       fully_matched_count = :fullyMatched
                 where batch_id = :batchId
                """, params);
        finalizeSampling(batchId);
    }

    private void materializeDetailCandidates(MapSqlParameterSource params) {
        if (transactionTemplate == null) {
            throw new IllegalStateException("采样明细生成需要事务管理器");
        }
        transactionTemplate.executeWithoutResult(status -> {
            jdbc.getJdbcTemplate().execute("drop table if exists tmp_sampling_detail_candidate");
            jdbc.getJdbcTemplate().execute("""
                    create temporary table tmp_sampling_detail_candidate (
                        group_id bigint not null,
                        candidate_id bigint not null,
                        sample_type varchar(32) not null,
                        dest_trcd varchar(200) not null,
                        service_code varchar(200) not null,
                        message_type varchar(32),
                        tran_code varchar(32) not null,
                        comp_result varchar(1) not null,
                        sop_field_name varchar(200) not null,
                        soap_field_name varchar(200),
                        bizjson_field_name varchar(200),
                        field_cn_name varchar(200),
                        orig_field_value varchar(2000),
                        dest_field_value varchar(2000),
                        mesg_seq varchar(64) not null,
                        conv_index integer,
                        conv_cindex integer,
                        field_index integer,
                        owner varchar(100),
                        affected_count bigint not null
                    ) on commit delete rows
                    """);
            jdbc.update("""
                    insert into tmp_sampling_detail_candidate (
                        group_id, candidate_id, sample_type, dest_trcd, service_code, message_type,
                        tran_code, comp_result, sop_field_name, soap_field_name, bizjson_field_name,
                        field_cn_name, orig_field_value, dest_field_value, mesg_seq, conv_index,
                        conv_cindex, field_index, owner, affected_count
                    )
                    select
                        g.group_id,
                        c.candidate_id,
                        c.sample_type,
                        c.dest_trcd,
                        c.service_code,
                        c.message_type,
                        c.tran_code,
                        c.comp_result,
                        c.sop_field_name,
                        c.soap_field_name,
                        c.bizjson_field_name,
                        c.field_cn_name,
                        c.orig_field_value,
                        c.dest_field_value,
                        c.mesg_seq,
                        c.conv_index,
                        c.conv_cindex,
                        c.field_index,
                        c.owner,
                        g.affected_count
                    from ana_sampling_candidate c
                    join ana_sample_group g
                      on g.batch_id = :batchId
                     and c.group_hash = g.group_hash
                     and c.group_key = g.group_key
                    where c.batch_id = :batchId
                    """, params);
            jdbc.getJdbcTemplate().execute("create index tmp_sampling_detail_candidate_small_idx on tmp_sampling_detail_candidate(affected_count, group_id, candidate_id)");
            jdbc.getJdbcTemplate().execute("create index tmp_sampling_detail_candidate_group_idx on tmp_sampling_detail_candidate(group_id, candidate_id)");
            jdbc.getJdbcTemplate().execute("analyze tmp_sampling_detail_candidate");
            insertSmallGroupDetails(params);
            insertLargeGroupDetails(params);
            jdbc.getJdbcTemplate().execute("drop table tmp_sampling_detail_candidate");
        });
    }

    private void insertSmallGroupDetails(MapSqlParameterSource params) {
        jdbc.update("""
                insert into ana_sample_detail (
                    group_id, batch_id, sample_type, sample_seq_no, dest_trcd, service_code, message_type,
                    tran_code, comp_result, sop_field_name, soap_field_name, bizjson_field_name,
                    field_cn_name, orig_field_value, dest_field_value, tran_seq_no, owner, affected_count,
                    source_table, source_pk
                )
                select
                    c.group_id,
                    :batchId,
                    c.sample_type,
                    row_number() over (partition by c.group_id order by c.candidate_id) as sample_seq_no,
                    c.dest_trcd,
                    c.service_code,
                    c.message_type,
                    c.tran_code,
                    c.comp_result,
                    c.sop_field_name,
                    c.soap_field_name,
                    c.bizjson_field_name,
                    c.field_cn_name,
                    c.orig_field_value,
                    c.dest_field_value,
                    c.mesg_seq,
                    c.owner,
                    c.affected_count,
                    'tss_field_comp',
                    c.mesg_seq || ':' || c.conv_index::text || ':' || c.conv_cindex::text || ':' ||
                        c.field_index::text || ':' || c.sop_field_name
                from tmp_sampling_detail_candidate c
                where c.affected_count <= 100
                """, params);
    }

    private void insertLargeGroupDetails(MapSqlParameterSource params) {
        jdbc.update("""
                insert into ana_sample_detail (
                    group_id, batch_id, sample_type, sample_seq_no, dest_trcd, service_code, message_type,
                    tran_code, comp_result, sop_field_name, soap_field_name, bizjson_field_name,
                    field_cn_name, orig_field_value, dest_field_value, tran_seq_no, owner, affected_count,
                    source_table, source_pk
                )
                select
                    group_id,
                    :batchId,
                    sample_type,
                    sample_seq_no,
                    dest_trcd,
                    service_code,
                    message_type,
                    tran_code,
                    comp_result,
                    sop_field_name,
                    soap_field_name,
                    bizjson_field_name,
                    field_cn_name,
                    orig_field_value,
                    dest_field_value,
                    mesg_seq,
                    owner,
                    affected_count,
                    'tss_field_comp',
                    mesg_seq || ':' || conv_index::text || ':' || conv_cindex::text || ':' ||
                        field_index::text || ':' || sop_field_name
                from (
                    select
                        c.group_id,
                        c.sample_type,
                        row_number() over (partition by group_id order by c.candidate_id) as sample_seq_no,
                        c.dest_trcd,
                        c.service_code,
                        c.message_type,
                        c.tran_code,
                        c.comp_result,
                        c.sop_field_name,
                        c.soap_field_name,
                        c.bizjson_field_name,
                        c.field_cn_name,
                        c.orig_field_value,
                        c.dest_field_value,
                        c.mesg_seq,
                        c.conv_index,
                        c.conv_cindex,
                        c.field_index,
                        c.owner,
                        c.affected_count
                    from tmp_sampling_detail_candidate c
                    where c.affected_count > 100
                ) c
                where sample_seq_no <= 100
                """, params);
    }

    private void materializeCandidates(MapSqlParameterSource params) {
        if (transactionTemplate == null) {
            throw new IllegalStateException("采样候选生成需要事务管理器");
        }
        transactionTemplate.executeWithoutResult(status -> {
            jdbc.getJdbcTemplate().execute("drop table if exists tmp_sampling_diff");
            jdbc.getJdbcTemplate().execute("""
                    create temporary table tmp_sampling_diff (
                        dest_trcd varchar(200) not null,
                        service_code varchar(200) not null,
                        message_type varchar(32),
                        comp_result varchar(1) not null,
                        orig_field_name varchar(200),
                        dest_field_name varchar(200),
                        orig_field_value varchar(2000),
                        dest_field_value varchar(2000),
                        mesg_seq varchar(64) not null,
                        conv_index integer,
                        conv_cindex integer,
                        field_index integer
                    ) on commit delete rows
                    """);
            jdbc.update("""
                    insert into tmp_sampling_diff (
                        dest_trcd, service_code, message_type, comp_result, orig_field_name, dest_field_name,
                        orig_field_value, dest_field_value, mesg_seq, conv_index, conv_cindex, field_index
                    )
                    select
                        f.dest_trcd,
                        split_part(f.dest_trcd, '&', 1) as service_code,
                        split_part(f.dest_trcd, '&', 2) as message_type,
                        t.comp_result,
                        f.orig_field_name,
                        f.dest_field_name,
                        f.orig_field_value,
                        f.dest_field_value,
                        f.mesg_seq,
                        f.conv_index,
                        f.conv_cindex,
                        f.field_index
                    from tss_field_comp f
                    join tss_tran_comp t
                      on t.mesg_seq = f.mesg_seq
                     and t.conv_index = f.conv_index
                     and t.conv_cindex = f.conv_cindex
                    where f.orig_cdate = :origCdate
                      and f.comp_result = '0'
                      and t.orig_cdate = :origCdate
                      and (
                            f.orig_field_name = 'returnCode'
                         or (
                                t.comp_result = '4'
                            and f.orig_field_name <> 'returnCode'
                         )
                      )
                    """, params);
            jdbc.getJdbcTemplate().execute("create index tmp_sampling_diff_mapping_idx on tmp_sampling_diff(service_code, orig_field_name, dest_field_name)");
            jdbc.getJdbcTemplate().execute("create index tmp_sampling_diff_seq_idx on tmp_sampling_diff(mesg_seq, conv_index, conv_cindex, field_index)");
            jdbc.getJdbcTemplate().execute("analyze tmp_sampling_diff");

            jdbc.update("""
                insert into ana_sampling_candidate (
                    batch_id, sample_type, group_key, group_hash, dest_trcd, service_code, message_type, tran_code,
                    comp_result, sop_field_name, soap_field_name, bizjson_field_name, field_cn_name,
                    orig_field_value, dest_field_value, mesg_seq, conv_index, conv_cindex, field_index, owner
                )
                select
                    :batchId,
                    case
                        when d.orig_field_name = 'returnCode' then 'RETURN_CODE'
                        else 'FIELD_DIFF'
                    end as sample_type,
                    :batchId || '|' ||
                        case
                            when d.orig_field_name = 'returnCode' then 'RETURN_CODE'
                            else 'FIELD_DIFF'
                        end || '|' || c.tran_code || '|' || d.service_code || '|' ||
                        coalesce(m.sop_field_name, d.orig_field_name) || '|' ||
                        coalesce(case when d.orig_field_name = 'returnCode' then d.orig_field_value else null end, '') || '|' ||
                        coalesce(case when d.orig_field_name = 'returnCode' then d.dest_field_value else null end, '') as group_key,
                    md5(:batchId || '|' ||
                        case
                            when d.orig_field_name = 'returnCode' then 'RETURN_CODE'
                            else 'FIELD_DIFF'
                        end || '|' || c.tran_code || '|' || d.service_code || '|' ||
                        coalesce(m.sop_field_name, d.orig_field_name) || '|' ||
                        coalesce(case when d.orig_field_name = 'returnCode' then d.orig_field_value else null end, '') || '|' ||
                        coalesce(case when d.orig_field_name = 'returnCode' then d.dest_field_value else null end, '')) as group_hash,
                    d.dest_trcd,
                    d.service_code,
                    d.message_type,
                    c.tran_code,
                    d.comp_result,
                    coalesce(m.sop_field_name, d.orig_field_name) as sop_field_name,
                    m.soap_field_name,
                    coalesce(m.bizjson_field_name, d.dest_field_name) as bizjson_field_name,
                    m.field_cn_name,
                    d.orig_field_value,
                    d.dest_field_value,
                    d.mesg_seq,
                    d.conv_index,
                    d.conv_cindex,
                    d.field_index,
                    c.owner
                from tmp_sampling_diff d
                join ana_tran_catalog c
                  on c.service_code = d.service_code
                left join ana_field_mapping m
                  on m.tran_code = c.tran_code
                 and m.service_code = c.service_code
                 and m.sop_field_name = d.orig_field_name
                 and m.bizjson_field_name = d.dest_field_name
                """, params);
            jdbc.getJdbcTemplate().execute("drop table tmp_sampling_diff");
        });
    }

    @Transactional
    public void writeTranChunk(List<? extends SamplingTranItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        SamplingTranItem first = items.get(0);
        List<String> mesgSeqs = items.stream().map(SamplingTranItem::mesgSeq).toList();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("batchId", first.batchId())
                .addValue("origCdate", first.origCdate())
                .addValue("mesgSeqs", mesgSeqs)
                .addValue("total", items.size())
                .addValue("comp1", countComp(items, "1"))
                .addValue("comp2", countComp(items, "2"))
                .addValue("comp3", countComp(items, "3"))
                .addValue("comp4", countComp(items, "4"))
                .addValue("comp8", countComp(items, "8"));

        jdbc.update("""
                insert into ana_sampling_candidate (
                    batch_id, sample_type, dest_trcd, service_code, message_type, tran_code,
                    comp_result, sop_field_name, soap_field_name, bizjson_field_name, field_cn_name,
                    orig_field_value, dest_field_value, mesg_seq, conv_index, conv_cindex, field_index, owner
                )
                select
                    :batchId,
                    case
                        when f.orig_field_name = 'returnCode' and f.comp_result = '0' then 'RETURN_CODE'
                        else 'FIELD_DIFF'
                    end as sample_type,
                    f.dest_trcd,
                    split_part(f.dest_trcd, '&', 1) as service_code,
                    split_part(f.dest_trcd, '&', 2) as message_type,
                    c.tran_code,
                    t.comp_result,
                    m.sop_field_name,
                    m.soap_field_name,
                    m.bizjson_field_name,
                    m.field_cn_name,
                    f.orig_field_value,
                    f.dest_field_value,
                    f.mesg_seq,
                    f.conv_index,
                    f.conv_cindex,
                    f.field_index,
                    c.owner
                from tss_tran_comp t
                join tss_field_comp f
                  on f.mesg_seq = t.mesg_seq
                 and f.conv_index = t.conv_index
                 and f.conv_cindex = t.conv_cindex
                join ana_tran_catalog c
                  on c.service_code = split_part(f.dest_trcd, '&', 1)
                join ana_field_mapping m
                  on m.tran_code = c.tran_code
                 and m.service_code = c.service_code
                 and m.sop_field_name = f.orig_field_name
                 and m.bizjson_field_name = f.dest_field_name
                where t.orig_cdate = :origCdate
                  and t.mesg_seq in (:mesgSeqs)
                  and f.orig_cdate = :origCdate
                  and (
                        (
                            f.orig_field_name = 'returnCode'
                            and f.comp_result = '0'
                        )
                     or (
                            t.comp_result = '4'
                            and f.comp_result = '0'
                            and f.orig_field_name <> 'returnCode'
                        )
                  )
                """, params);

        jdbc.update(groupedCandidateCte() + """
                update ana_sample_group g
                   set affected_count = g.affected_count + s.affected_count,
                       sample_count = least(g.affected_count + s.affected_count, 100),
                       updated_at = current_timestamp
                  from grouped s
                 where g.batch_id = :batchId
                   and g.group_key = s.group_key
                """, params);
        jdbc.update(groupedCandidateCte() + """
                insert into ana_sample_group (
                    batch_id, sample_type, group_key, dest_trcd, service_code, message_type, tran_code,
                    comp_result, sop_field_name, soap_field_name, bizjson_field_name, field_cn_name,
                    orig_field_value, dest_field_value, owner, affected_count, sample_count
                )
                select
                    :batchId, sample_type, group_key, dest_trcd, service_code, message_type, tran_code,
                    comp_result, sop_field_name, soap_field_name, bizjson_field_name, field_cn_name,
                    orig_field_value, dest_field_value, owner, affected_count, least(affected_count, 100)
                from grouped s
                where not exists (
                    select 1
                    from ana_sample_group g
                    where g.batch_id = :batchId
                      and g.group_key = s.group_key
                )
                """, params);

        jdbc.update("""
                insert into ana_sample_detail (
                    group_id, batch_id, sample_type, sample_seq_no, dest_trcd, service_code, message_type,
                    tran_code, comp_result, sop_field_name, soap_field_name, bizjson_field_name,
                    field_cn_name, orig_field_value, dest_field_value, tran_seq_no, owner, affected_count,
                    source_table, source_pk
                )
                select
                    g.group_id,
                    :batchId,
                    c.sample_type,
                    1,
                    c.dest_trcd,
                    c.service_code,
                    c.message_type,
                    c.tran_code,
                    c.comp_result,
                    c.sop_field_name,
                    c.soap_field_name,
                    c.bizjson_field_name,
                    c.field_cn_name,
                    c.orig_field_value,
                    c.dest_field_value,
                    c.mesg_seq,
                    c.owner,
                    g.affected_count,
                    'tss_field_comp',
                    c.mesg_seq || ':' || c.conv_index::text || ':' || c.conv_cindex::text || ':' ||
                        c.field_index::text || ':' || c.sop_field_name
                from (
                    select distinct on (
                        sample_type, tran_code, service_code, sop_field_name,
                        case when sample_type = 'RETURN_CODE' then orig_field_value else null end,
                        case when sample_type = 'RETURN_CODE' then dest_field_value else null end
                    ) *
                    from ana_sampling_candidate
                    where batch_id = :batchId
                      and mesg_seq in (:mesgSeqs)
                    order by
                        sample_type, tran_code, service_code, sop_field_name,
                        case when sample_type = 'RETURN_CODE' then orig_field_value else null end,
                        case when sample_type = 'RETURN_CODE' then dest_field_value else null end,
                        candidate_id
                ) c
                join ana_sample_group g
                  on g.batch_id = :batchId
                 and g.group_key = :batchId || '|' || c.sample_type || '|' || c.tran_code || '|' || c.service_code || '|' ||
                    c.sop_field_name || '|' ||
                    coalesce(case when c.sample_type = 'RETURN_CODE' then c.orig_field_value else null end, '') || '|' ||
                    coalesce(case when c.sample_type = 'RETURN_CODE' then c.dest_field_value else null end, '')
                where not exists (
                    select 1
                    from ana_sample_detail d
                    where d.group_id = g.group_id
                      and d.sample_seq_no = 1
                )
                """, params);

        Long issueFieldCount = jdbc.queryForObject("""
                select count(*) from (
                    select distinct f.mesg_seq, f.conv_index, f.conv_cindex, f.orig_field_name
                    from tss_field_comp f
                    where f.orig_cdate = :origCdate
                      and f.mesg_seq in (:mesgSeqs)
                      and f.comp_result = '0'
                ) d
                """, params, Long.class);
        Long fullyMatchedCount = jdbc.queryForObject("""
                select count(*)
                from tss_tran_comp t
                where t.orig_cdate = :origCdate
                  and t.mesg_seq in (:mesgSeqs)
                  and not exists (
                      select 1
                      from tss_field_comp f
                      where f.mesg_seq = t.mesg_seq
                        and f.conv_index = t.conv_index
                        and f.conv_cindex = t.conv_cindex
                        and f.comp_result = '0'
                  )
                """, params, Long.class);
        params.addValue("issueFields", issueFieldCount == null ? 0L : issueFieldCount);
        params.addValue("fullyMatched", fullyMatchedCount == null ? 0L : fullyMatchedCount);
        jdbc.update("""
                update ana_sampling_summary
                   set total_tran_count = total_tran_count + :total,
                       comp_result_1_count = comp_result_1_count + :comp1,
                       comp_result_2_count = comp_result_2_count + :comp2,
                       comp_result_3_count = comp_result_3_count + :comp3,
                       comp_result_4_count = comp_result_4_count + :comp4,
                       comp_result_8_count = comp_result_8_count + :comp8,
                       pass_tran_count = pass_tran_count + :comp4,
                       issue_field_count = issue_field_count + :issueFields,
                       fully_matched_count = fully_matched_count + :fullyMatched
                 where batch_id = :batchId
                """, params);
    }

    @Transactional
    public void finalizeSampling(String batchId) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("batchId", batchId);
        jdbc.update("""
                update ana_sampling_summary
                   set sample_group_count = coalesce((select count(*) from ana_sample_group where batch_id = :batchId), 0),
                       sample_detail_count = coalesce((select count(*) from ana_sample_detail where batch_id = :batchId), 0)
                 where batch_id = :batchId
                """, params);
        jdbc.update("delete from ana_sampling_candidate where batch_id = :batchId", params);
    }

    private long countComp(List<? extends SamplingTranItem> items, String compResult) {
        return items.stream().filter(item -> compResult.equals(item.compResult())).count();
    }

    private String groupedCandidateCte() {
        return """
                with grouped as (
                    select
                        sample_type,
                        :batchId || '|' || sample_type || '|' || tran_code || '|' || service_code || '|' ||
                            sop_field_name || '|' ||
                            coalesce(min(case when sample_type = 'RETURN_CODE' then orig_field_value else null end), '') || '|' ||
                            coalesce(min(case when sample_type = 'RETURN_CODE' then dest_field_value else null end), '') as group_key,
                        md5(:batchId || '|' || sample_type || '|' || tran_code || '|' || service_code || '|' ||
                            sop_field_name || '|' ||
                            coalesce(min(case when sample_type = 'RETURN_CODE' then orig_field_value else null end), '') || '|' ||
                            coalesce(min(case when sample_type = 'RETURN_CODE' then dest_field_value else null end), '')) as group_hash,
                        min(dest_trcd) as dest_trcd,
                        service_code,
                        min(message_type) as message_type,
                        tran_code,
                        case when sample_type = 'FIELD_DIFF' then '4' else min(comp_result) end as comp_result,
                        sop_field_name,
                        min(soap_field_name) as soap_field_name,
                        min(bizjson_field_name) as bizjson_field_name,
                        min(field_cn_name) as field_cn_name,
                        min(case when sample_type = 'RETURN_CODE' then orig_field_value else null end) as orig_field_value,
                        min(case when sample_type = 'RETURN_CODE' then dest_field_value else null end) as dest_field_value,
                        min(owner) as owner,
                        count(*) as affected_count
                    from ana_sampling_candidate
                    where batch_id = :batchId
                      and mesg_seq in (:mesgSeqs)
                    group by sample_type, tran_code, service_code, sop_field_name,
                             case when sample_type = 'RETURN_CODE' then orig_field_value else null end,
                             case when sample_type = 'RETURN_CODE' then dest_field_value else null end
                )
                """;
    }

    private String allCandidateGroupsCte() {
        return """
                with grouped as (
                    select
                        sample_type,
                        :batchId || '|' || sample_type || '|' || tran_code || '|' || service_code || '|' ||
                            sop_field_name || '|' ||
                            coalesce(min(case when sample_type = 'RETURN_CODE' then orig_field_value else null end), '') || '|' ||
                            coalesce(min(case when sample_type = 'RETURN_CODE' then dest_field_value else null end), '') as group_key,
                        md5(:batchId || '|' || sample_type || '|' || tran_code || '|' || service_code || '|' ||
                            sop_field_name || '|' ||
                            coalesce(min(case when sample_type = 'RETURN_CODE' then orig_field_value else null end), '') || '|' ||
                            coalesce(min(case when sample_type = 'RETURN_CODE' then dest_field_value else null end), '')) as group_hash,
                        min(dest_trcd) as dest_trcd,
                        service_code,
                        min(message_type) as message_type,
                        tran_code,
                        case when sample_type = 'FIELD_DIFF' then '4' else min(comp_result) end as comp_result,
                        sop_field_name,
                        min(soap_field_name) as soap_field_name,
                        min(bizjson_field_name) as bizjson_field_name,
                        min(field_cn_name) as field_cn_name,
                        min(case when sample_type = 'RETURN_CODE' then orig_field_value else null end) as orig_field_value,
                        min(case when sample_type = 'RETURN_CODE' then dest_field_value else null end) as dest_field_value,
                        min(owner) as owner,
                        count(*) as affected_count
                    from ana_sampling_candidate
                    where batch_id = :batchId
                    group by sample_type, tran_code, service_code, sop_field_name,
                             case when sample_type = 'RETURN_CODE' then orig_field_value else null end,
                             case when sample_type = 'RETURN_CODE' then dest_field_value else null end
                )
                """;
    }

    private Long countTranByResult(MapSqlParameterSource params, String compResult) {
        return jdbc.queryForObject("""
                select count(*)
                from tss_tran_comp t
                join (
                    select distinct service_code
                    from ana_tran_catalog
                ) c
                  on c.service_code = split_part(t.dest_trcd, '&', 1)
                where t.orig_cdate = :origCdate
                  and t.comp_result = :compResult
                """, new MapSqlParameterSource()
                .addValue("origCdate", params.getValue("origCdate"))
                .addValue("compResult", compResult), Long.class);
    }

    public void markCompleted(String batchId) {
        jdbc.update("""
                update ana_sampling_command c
                   set status = 'COMPLETED',
                       ended_time = current_timestamp,
                       total_tran_count = coalesce((select total_tran_count from ana_sampling_summary where batch_id = c.batch_id), 0),
                       field_diff_count = coalesce((select issue_field_count from ana_sampling_summary where batch_id = c.batch_id), 0),
                       sample_group_count = coalesce((select count(*) from ana_sample_group where batch_id = c.batch_id), 0),
                       sample_detail_count = coalesce((select count(*) from ana_sample_detail where batch_id = c.batch_id), 0)
                 where c.batch_id = :batchId
                """, new MapSqlParameterSource().addValue("batchId", batchId));
    }

    public void markFailed(String batchId, String errorMessage) {
        jdbc.update("""
                update ana_sampling_command
                   set status = 'FAILED', ended_time = current_timestamp, error_message = :errorMessage
                 where batch_id = :batchId
                """, new MapSqlParameterSource()
                .addValue("batchId", batchId)
                .addValue("errorMessage", abbreviate(errorMessage == null ? "采样执行失败" : errorMessage, 2000)));
    }

    private void validate(SamplingCommandForm form) {
        if (form == null || !StringUtils.hasText(form.origCdate())) {
            throw new IllegalArgumentException("orig_cdate不能为空");
        }
        normalizeOrigCdate(form.origCdate());
    }

    private String normalizeOrigCdate(String origCdate) {
        String value = origCdate == null ? "" : origCdate.trim();
        if (!value.matches("\\d{8}")) {
            throw new IllegalArgumentException("orig_cdate必须是8位日期，例如20260608");
        }
        return value;
    }

    private String textOrNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private QueryParts where(SamplingCommandSearchCriteria criteria) {
        List<String> clauses = new ArrayList<>();
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (criteria != null) {
            like(clauses, params, "c.batch_id", "batchId", criteria.batchId());
            eq(clauses, params, "c.orig_cdate", "origCdate", criteria.origCdate());
            eq(clauses, params, "c.status", "status", criteria.status());
        }
        return new QueryParts(clauses.isEmpty() ? "" : " where " + String.join(" and ", clauses), params);
    }

    private void like(List<String> clauses, MapSqlParameterSource params, String column, String key, String value) {
        if (StringUtils.hasText(value)) {
            clauses.add(column + " like :" + key);
            params.addValue(key, "%" + value.trim() + "%");
        }
    }

    private void eq(List<String> clauses, MapSqlParameterSource params, String column, String key, String value) {
        if (StringUtils.hasText(value)) {
            clauses.add(column + " = :" + key);
            params.addValue(key, value.trim());
        }
    }

    private Long getLongOrNull(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private String formatDuration(Long seconds) {
        if (seconds == null || seconds < 0) {
            return "-";
        }
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long remainingSeconds = seconds % 60;
        if (hours > 0) {
            return hours + "时" + minutes + "分" + remainingSeconds + "秒";
        }
        if (minutes > 0) {
            return minutes + "分" + remainingSeconds + "秒";
        }
        return remainingSeconds + "秒";
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private record QueryParts(String where, MapSqlParameterSource params) {}
}
