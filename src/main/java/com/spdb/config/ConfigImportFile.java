package com.spdb.config;

import java.nio.file.Path;

public record ConfigImportFile(
        Path path,
        String originalFilename
) {
}
