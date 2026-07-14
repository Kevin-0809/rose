# Jasypt 数据库密码加密设计

## 目标

使用 Jasypt 加密主数据源和 BXDS 数据源的数据库密码，避免在仓库中保存明文密码。

## 方案

引入 `jasypt-spring-boot-starter`。在 `application.properties` 中将下列配置替换为 `ENC(...)` 密文：

- `spring.datasource.password`
- `rose.datasource.bxds.password`

应用启动时由 Jasypt 自动解密配置值。解密主密钥只从环境变量
`JASYPT_ENCRYPTOR_PASSWORD` 读取，不写入项目配置、测试资源或版本控制。

## 保持不变的部分

数据库 URL、用户名、驱动和现有的主/BXDS 双数据源装配保持不变。已有的
`DataSourceProperties` 配置绑定继续作为 Jasypt 解密后的密码消费方。

## 验证

增加 Spring 上下文测试：向测试上下文提供主密钥，验证主数据源和 BXDS
数据源的 `DataSourceProperties` 都能取得预期的明文密码。该测试不连接真实数据库。

## 失败处理

若未设置主密钥或主密钥不正确，应用会在读取加密配置时失败。部署环境必须为应用进程
注入 `JASYPT_ENCRYPTOR_PASSWORD`。
