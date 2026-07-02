package com.spdb.migration;

public record MigrationSqlCommandForm(
        String responseSql,
        String remark
) {
    public static MigrationSqlCommandForm empty() {
        return new MigrationSqlCommandForm("", "");
    }
}
