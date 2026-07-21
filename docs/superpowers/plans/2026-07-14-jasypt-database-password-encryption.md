# Jasypt 数据库密码加密实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 以 Jasypt 密文保存主数据源和 BXDS 数据源密码，并让 Spring Boot 在绑定数据源前自动解密。

**Architecture:** 引入 Jasypt Spring Boot Starter，其配置解密器从环境变量 `JASYPT_ENCRYPTOR_PASSWORD` 取得主密钥。`application.properties` 中两个密码使用 `ENC(...)` 值，既有 `DataSourceProperties` 和双数据源配置无需修改。

**Tech Stack:** Java 17、Spring Boot 3.3.6、Maven、jasypt-spring-boot-starter 3.0.5、JUnit 5。

---

## 文件结构

- 修改 `pom.xml`：加入 Jasypt Starter，使 Spring 环境属性可识别 `ENC(...)`。
- 修改 `src/main/resources/application.properties`：将两个数据库密码替换为 Jasypt 密文。
- 修改 `src/test/java/com/spdb/migration/MigrationDataSourceConfigTest.java`：添加解密绑定覆盖。

### Task 1: 验证加密配置绑定

**Files:**
- Modify: `src/test/java/com/spdb/migration/MigrationDataSourceConfigTest.java`

- [ ] **Step 1: 写出会失败的上下文测试**

在测试类中添加 Jasypt 自动配置和如下测试。密文由 `jasypt-spring-boot-starter` 的默认 `PBEWITHHMACSHA512ANDAES_256` 算法、主密钥 `test-master-password` 生成；断言数据源绑定后得到明文。

```java
@Test
void decryptsPasswordsBeforeBindingBothDataSources() {
    encryptedContextRunner.run(context -> {
        DataSourceProperties primary = context.getBean("primaryDataSourceProperties", DataSourceProperties.class);
        DataSourceProperties bxds = context.getBean("bxdsDataSourceProperties", DataSourceProperties.class);

        assertThat(primary.getPassword()).isEqualTo("primary-secret");
        assertThat(bxds.getPassword()).isEqualTo("bxds-secret");
    });
}
```

- [ ] **Step 2: 运行测试，确认因缺少 Jasypt 依赖而失败**

Run: `mvn -Dtest=MigrationDataSourceConfigTest#decryptsPasswordsBeforeBindingBothDataSources test`

Expected: 编译失败，提示缺少 Jasypt 自动配置类或测试属性未被解密。

- [ ] **Step 3: 加入最小依赖配置**

在 `pom.xml` 的 `<dependencies>` 中加入：

```xml
<dependency>
    <groupId>com.github.ulisesbocchio</groupId>
    <artifactId>jasypt-spring-boot-starter</artifactId>
    <version>3.0.5</version>
</dependency>
```

- [ ] **Step 4: 再次运行定向测试，确认通过**

Run: `mvn -Dtest=MigrationDataSourceConfigTest#decryptsPasswordsBeforeBindingBothDataSources test`

Expected: `BUILD SUCCESS`，两个 `DataSourceProperties` 取得对应明文。

### Task 2: 用密文替换默认数据库密码

**Files:**
- Modify: `src/main/resources/application.properties`
- Modify: `src/test/java/com/spdb/migration/MigrationDataSourceConfigTest.java`

- [ ] **Step 1: 写出会失败的默认配置断言**

将 `applicationDefaultsPointBxdsToAdpTestDatabase` 中的 BXDS 密码断言改为校验以 `ENC(` 开头、以 `)` 结尾；增加主数据源密码的同样断言：

```java
assertThat(properties.getProperty("spring.datasource.password"))
        .startsWith("ENC(")
        .endsWith(")");
assertThat(properties.getProperty("rose.datasource.bxds.password"))
        .startsWith("ENC(")
        .endsWith(")");
```

- [ ] **Step 2: 运行测试，确认仍为明文导致失败**

Run: `mvn -Dtest=MigrationDataSourceConfigTest#applicationDefaultsPointBxdsToAdpTestDatabase test`

Expected: 断言失败，显示一个或两个密码不是 `ENC(...)` 格式。

- [ ] **Step 3: 生成并写入密文**

使用临时环境变量 `JASYPT_ENCRYPTOR_PASSWORD` 生成原有主数据源密码 `Tss@123456` 与 BXDS 密码 `OpenGauss@123` 的密文。仅将两个 `ENC(...)` 结果写入 `application.properties`，不得将主密钥、明文密码或生成命令中的密钥写入仓库。

```properties
spring.datasource.password=ENC(<primary-ciphertext>)
rose.datasource.bxds.password=${BXDS_DATASOURCE_PASSWORD:ENC(<bxds-ciphertext>)}
```

- [ ] **Step 4: 运行定向测试，确认密文格式通过**

Run: `mvn -Dtest=MigrationDataSourceConfigTest#applicationDefaultsPointBxdsToAdpTestDatabase test`

Expected: `BUILD SUCCESS`。

- [ ] **Step 5: 提交实现**

```bash
git add pom.xml src/main/resources/application.properties src/test/java/com/spdb/migration/MigrationDataSourceConfigTest.java
git commit -m "feat: encrypt database passwords with Jasypt"
```

### Task 3: 全量验证

**Files:**
- Verify only: `pom.xml`
- Verify only: `src/main/resources/application.properties`
- Verify only: `src/test/java/com/spdb/migration/MigrationDataSourceConfigTest.java`

- [ ] **Step 1: 运行全部测试**

Run: `mvn test`

Expected: `BUILD SUCCESS`，无失败或错误。

- [ ] **Step 2: 检查未跟踪明文或主密钥**

Run: `rg -n "JASYPT_ENCRYPTOR_PASSWORD=|test-master-password" . -g '!target/**' -g '!docs/superpowers/**'`

Expected: 没有可提交的主密钥；仅测试代码中的固定测试密钥可存在。

- [ ] **Step 3: 检查改动范围**

Run: `git diff --check HEAD~1 HEAD && git status --short`

Expected: 无空白错误，且只包含本任务文件及用户已有的未提交改动。
