package com.spdb.config;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.apache.poi.ss.usermodel.Workbook;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ConfigImportService {
    private final ConfigWorkbookParser parser;
    private final NamedParameterJdbcTemplate jdbc;

    public ConfigImportService(ConfigWorkbookParser parser, NamedParameterJdbcTemplate jdbc) {
        this.parser = parser;
        this.jdbc = jdbc;
    }

    public ParsedConfigImport preview(Path path, String originalFilename, String serviceCode,
                                      String moduleName, String owner) throws IOException {
        return parser.parse(path, originalFilename, serviceCode, moduleName, owner);
    }

    public List<ParsedConfigImport> previewWorkbooks(List<ConfigImportFile> files, String serviceCode,
                                                     String moduleName, String owner) throws IOException {
        List<ParsedConfigImport> previews = new ArrayList<>();
        for (ConfigImportFile file : files) {
            previews.add(parser.parse(file.path(), file.originalFilename(), serviceCode, moduleName, owner));
        }
        return previews;
    }

    @Transactional
    public ConfigImportResult importWorkbook(Path path, String originalFilename, String serviceCode,
                                             String moduleName, String owner) throws IOException {
        ParsedConfigImport parsed = parser.parse(path, originalFilename, serviceCode, moduleName, owner);
        return importParsed(parsed);
    }

    @Transactional
    public ConfigImportBatchResult importWorkbooks(List<ConfigImportFile> files, String serviceCode,
                                                   String moduleName, String owner) throws IOException {
        List<ConfigImportResult> results = new ArrayList<>();
        int tranInserted = 0;
        int tranUpdated = 0;
        int fieldInserted = 0;
        int fieldUpdated = 0;
        int fieldSkipped = 0;
        for (ConfigImportFile file : files) {
            List<ParsedConfigImport> parsedImports = parser.parseAll(file.path(), file.originalFilename(), serviceCode, moduleName, owner);
            for (ParsedConfigImport parsed : parsedImports) {
                ConfigImportResult result = importParsed(parsed);
                results.add(result);
                tranInserted += result.tranInserted();
                tranUpdated += result.tranUpdated();
                fieldInserted += result.fieldInserted();
                fieldUpdated += result.fieldUpdated();
                fieldSkipped += result.fieldSkipped();
            }
        }
        return new ConfigImportBatchResult(tranInserted, tranUpdated, fieldInserted, fieldUpdated, fieldSkipped, results);
    }

    @Transactional
    public ConfigImportBatchResult importParsedWorkbooks(List<Workbook> workbooks, List<TransactionListEntry> entries) {
        Map<String, TransactionListEntry> entryByTranCode = entries.stream()
                .collect(Collectors.toMap(TransactionListEntry::tranCode, Function.identity(), (first, ignored) -> first));
        List<ConfigImportResult> results = new ArrayList<>();
        int tranInserted = 0;
        int tranUpdated = 0;
        int fieldInserted = 0;
        int fieldUpdated = 0;
        int fieldSkipped = 0;
        for (Workbook workbook : workbooks) {
            List<ParsedConfigImport> parsedImports = parser.parseAll(workbook, "mapping.xlsx", "", "", "");
            for (ParsedConfigImport parsed : parsedImports) {
                TransactionListEntry entry = entryByTranCode.get(parsed.tran().tranCode());
                if (entry == null) {
                    fieldSkipped += parsed.fields().size();
                    continue;
                }
                ConfigImportResult result = importParsed(withEntryMetadata(parsed, entry));
                results.add(result);
                tranInserted += result.tranInserted();
                tranUpdated += result.tranUpdated();
                fieldInserted += result.fieldInserted();
                fieldUpdated += result.fieldUpdated();
                fieldSkipped += result.fieldSkipped();
            }
        }
        return new ConfigImportBatchResult(tranInserted, tranUpdated, fieldInserted, fieldUpdated, fieldSkipped, results);
    }

    private ParsedConfigImport withEntryMetadata(ParsedConfigImport parsed, TransactionListEntry entry) {
        ParsedTranImport tran = parsed.tran();
        return new ParsedConfigImport(
                new ParsedTranImport(
                        tran.tranCode(),
                        tran.serviceCode(),
                        tran.tranName(),
                        entry.moduleName(),
                        entry.owner(),
                        tran.remark()
                ),
                parsed.fields(),
                parsed.warnings()
        );
    }

    private ConfigImportResult importParsed(ParsedConfigImport parsed) {
        int tranUpdated = updateTran(parsed.tran());
        int tranInserted = 0;
        if (tranUpdated == 0) {
            insertTran(parsed.tran());
            tranInserted = 1;
        }

        int fieldInserted = 0;
        int fieldUpdated = 0;
        int fieldSkipped = 0;
        for (ParsedFieldImport field : parsed.fields()) {
            int updated = updateField(field);
            if (updated == 0) {
                insertField(field);
                fieldInserted++;
            } else {
                fieldUpdated++;
            }
        }
        return new ConfigImportResult(tranInserted, tranUpdated, fieldInserted, fieldUpdated, fieldSkipped, parsed);
    }

    private int updateTran(ParsedTranImport tran) {
        return jdbc.update("""
                update ana_tran_catalog
                set tran_name = :tranName,
                    module_name = :moduleName,
                    owner = :owner,
                    is_key_tran = 'false',
                    remark = :remark,
                    updated_at = current_timestamp
                where tran_code = :tranCode
                  and service_code = :serviceCode
                """, tranParams(tran));
    }

    private void insertTran(ParsedTranImport tran) {
        jdbc.update("""
                insert into ana_tran_catalog (
                    tran_code, service_code, tran_name, module_name, owner,
                    importance_level, is_key_tran, remark
                ) values (
                    :tranCode, :serviceCode, :tranName, :moduleName, :owner,
                    null, 'false', :remark
                )
                """, tranParams(tran));
    }

    private int updateField(ParsedFieldImport field) {
        return jdbc.update("""
                update ana_field_mapping
                set field_cn_name = :fieldCnName,
                    sop_field_name = :sopFieldName,
                    soap_field_name = :soapFieldName,
                    bizjson_field_name = :bizjsonFieldName,
                    remark = :remark,
                    updated_at = current_timestamp
                where tran_code = :tranCode
                  and service_code = :serviceCode
                  and std_field_name = :stdFieldName
                """, fieldParams(field));
    }

    private void insertField(ParsedFieldImport field) {
        jdbc.update("""
                insert into ana_field_mapping (
                    tran_code, service_code, std_field_name, field_cn_name,
                    sop_field_name, soap_field_name, bizjson_field_name, remark
                ) values (
                    :tranCode, :serviceCode, :stdFieldName, :fieldCnName,
                    :sopFieldName, :soapFieldName, :bizjsonFieldName, :remark
                )
                """, fieldParams(field));
    }

    private MapSqlParameterSource tranParams(ParsedTranImport tran) {
        return new MapSqlParameterSource()
                .addValue("tranCode", tran.tranCode())
                .addValue("serviceCode", tran.serviceCode())
                .addValue("tranName", tran.tranName())
                .addValue("moduleName", tran.moduleName())
                .addValue("owner", tran.owner())
                .addValue("remark", tran.remark());
    }

    private MapSqlParameterSource fieldParams(ParsedFieldImport field) {
        return new MapSqlParameterSource()
                .addValue("tranCode", field.tranCode())
                .addValue("serviceCode", field.serviceCode())
                .addValue("stdFieldName", field.stdFieldName())
                .addValue("fieldCnName", field.fieldCnName())
                .addValue("sopFieldName", field.sopFieldName())
                .addValue("soapFieldName", field.soapFieldName())
                .addValue("bizjsonFieldName", field.bizjsonFieldName())
                .addValue("remark", field.remark());
    }
}
