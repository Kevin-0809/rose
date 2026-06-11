package com.spdb;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PackageNameMigrationTest {

    @Test
    void sourcePackagesUseComSpdbNamespace() throws Exception {
        List<Path> files;
        try (var paths = Files.walk(Path.of("src"))) {
            files = paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList();
        }

        List<Path> oldNamespaceFiles = files.stream()
                .filter(path -> containsOldNamespace(path))
                .toList();

        assertThat(oldNamespaceFiles).isEmpty();
    }

    private boolean containsOldNamespace(Path path) {
        try {
            String oldNamespace = "cn.sunline" + ".rose";
            return Files.readString(path, StandardCharsets.UTF_8).contains(oldNamespace);
        } catch (Exception e) {
            throw new IllegalStateException("读取源码失败: " + path, e);
        }
    }
}
