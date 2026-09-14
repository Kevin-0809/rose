package com.spdb.migration;

public record MigrationSqlCommandForm(
        String requestSql,
        String remark
) {
    public static MigrationSqlCommandForm empty() {
        return new MigrationSqlCommandForm("", "");
    }
}
