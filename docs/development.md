# 开发环境指南

本文档用于补充贡献者在本地构建和调试 LeoAI 时常用的环境准备步骤。

## 环境要求

- JDK 17 或更高版本
- Maven 3.8 或更高版本
- Node.js 18 或更高版本（仅在调试前端源码时需要）

## 后端构建

在仓库根目录执行：

```bash
mvn package -DskipTests
```

如果只想验证模块能否编译，可以执行：

```bash
mvn compile
```

## 本地运行

构建完成后，可以使用生成的 JAR 启动服务。由于项目依赖对 JDK 内部 API 的访问，启动时需要保留 `--add-opens` 参数：

```bash
java -jar --add-opens java.base/java.lang=ALL-UNNAMED target/LeoAi-0.0.2-SNAPSHOT.jar
```

服务默认监听 `8082` 端口，启动后访问：

```text
http://localhost:8082
```

如需临时调整端口，可追加 Spring Boot 参数：

```bash
java -jar --add-opens java.base/java.lang=ALL-UNNAMED target/LeoAi-0.0.2-SNAPSHOT.jar --server.port=9090
```

## 数据库文件

LeoAI 默认使用 SQLite。首次启动时会在运行目录下创建数据库文件，调试不同分支或功能时建议使用独立的工作目录，避免本地数据互相影响。

也可以通过启动参数指定数据库位置：

```bash
java -jar --add-opens java.base/java.lang=ALL-UNNAMED target/LeoAi-0.0.2-SNAPSHOT.jar \
  --spring.datasource.url=jdbc:sqlite:/path/to/data.db
```

## 前端调试

仓库内已包含打包后的静态资源。如果需要调试前端源码，请先进入前端模块目录，再安装依赖并启动本地开发服务：

```bash
cd web
npm install
npm run dev
```

前端开发服务用于界面调试；提交前仍建议在仓库根目录执行 Maven 构建，确认后端打包流程正常。

## 提交前检查

提交 Pull Request 前建议至少完成以下检查：

- 文档类改动：确认 Markdown 链接和命令示例可读。
- Java 代码改动：执行 `mvn compile` 或 `mvn package -DskipTests`。
- 前端代码改动：执行前端模块已有的 lint 或 build 脚本。
