package com.spdb.config;

import java.util.List;

public record ParsedConfigImport(
        ParsedTranImport tran,
        List<ParsedFieldImport> fields,
        List<String> warnings
) {
}
