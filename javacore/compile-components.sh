#!/usr/bin/env bash
# 使用 Java 8 javac 或 ECJ 以 Java 6 级别编译所有 component 类
# 生成 major version 50 (Java 6) 的 .payload 放入 src/main/resources/component/
#
# 用法:
#   cd LeoAI/javacore && bash compile-components.sh          # 审计并更新 payload
#   cd LeoAI/javacore && bash compile-components.sh --check  # 仅审计，不写 resources
#
# 优先使用可工作的 Java 8 javac；也支持在当前 JDK 上运行 ECJ 3.27，
# ECJ 制品缺失时通过 Maven 本地仓库解析。
# -source 1.6 限制语言特性（无 lambda/diamond/try-with-resources）
# -target 1.6 生成 major version 50 字节码
# CloneWithJavassist 保留 major version 50，使命名、语法年代与字节码版本一致

set -euo pipefail

MODE="${1:-}"
if [ -n "$MODE" ] && [ "$MODE" != "--check" ]; then
    echo "用法: $0 [--check]" >&2
    exit 2
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
SRC_DIR="$SCRIPT_DIR/src/main/java/org/leo/core/component"
OUT_DIR="$SCRIPT_DIR/src/main/resources/component"
TMP_DIR=$(mktemp -d)

cleanup() {
    rm -rf "$TMP_DIR"
}
trap cleanup EXIT

AUDIT_POM="$TMP_DIR/component-api-audit/pom.xml"

# Animal Sniffer 需要一个 Maven project 才能执行。将这个仅用于审计的
# 配置放入临时目录，避免编译流程依赖工作树中是否保留辅助 POM。
mkdir -p "$(dirname "$AUDIT_POM")"
cat > "$AUDIT_POM" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId>org.leo.build</groupId>
    <artifactId>component-api-audit</artifactId>
    <version>1</version>
    <properties>
        <component.classes.dir>${project.basedir}/classes</component.classes.dir>
    </properties>
    <build>
        <outputDirectory>${component.classes.dir}</outputDirectory>
        <plugins>
            <plugin>
                <groupId>org.codehaus.mojo</groupId>
                <artifactId>animal-sniffer-maven-plugin</artifactId>
                <version>1.27</version>
                <configuration>
                    <signature>
                        <groupId>org.codehaus.mojo.signature</groupId>
                        <artifactId>java16</artifactId>
                        <version>1.1</version>
                    </signature>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
EOF

resolve_java8_home() {
    if [ -n "${JAVA8_HOME:-}" ] && usable_java8_home "$JAVA8_HOME"; then
        printf '%s\n' "$JAVA8_HOME"; return 0
    fi
    if command -v /usr/libexec/java_home >/dev/null 2>&1; then
        local mac_home
        mac_home=$(/usr/libexec/java_home -v 1.8 2>/dev/null || true)
        if [ -n "$mac_home" ] && usable_java8_home "$mac_home"; then
            printf '%s\n' "$mac_home"; return 0
        fi
    fi
    if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ] \
            && "$JAVA_HOME/bin/javac" -version 2>&1 | grep -q 'javac 1\.8\.'; then
        printf '%s\n' "$JAVA_HOME"
        return 0
    fi
    if command -v javac >/dev/null 2>&1 \
            && javac -version 2>&1 | grep -q 'javac 1\.8\.'; then
        dirname "$(dirname "$(command -v javac)")"
        return 0
    fi
    return 1
}

usable_java8_home() {
    local home="$1"
    [ -x "$home/bin/javac" ] || return 1
    "$home/bin/javac" -J-Xms16m -J-Xmx64m -version 2>&1 | grep -q 'javac 1\.8\.'
}

resolve_ecj_jar() {
    local version="3.27.0"
    local jar="$HOME/.m2/repository/org/eclipse/jdt/ecj/$version/ecj-$version.jar"
    if [ ! -f "$jar" ]; then
        "$REPO_DIR/mvnw" -q org.apache.maven.plugins:maven-dependency-plugin:3.8.1:get \
            -Dartifact="org.eclipse.jdt:ecj:$version"
    fi
    [ -f "$jar" ] && printf '%s\n' "$jar"
}

JAVA8_HOME_RESOLVED=$(resolve_java8_home || true)
JAVA8_API_HOME="${JAVA8_HOME:-}"
if command -v /usr/libexec/java_home >/dev/null 2>&1; then
    JAVA8_API_HOME=$(/usr/libexec/java_home -v 1.8 2>/dev/null || printf '%s' "$JAVA8_API_HOME")
fi
JAVAP_HOME=""
if command -v /usr/libexec/java_home >/dev/null 2>&1; then
    JAVAP_HOME=$(/usr/libexec/java_home -v 17 2>/dev/null || /usr/libexec/java_home 2>/dev/null || true)
fi
if [ -z "$JAVAP_HOME" ]; then JAVAP_HOME="${JAVA_HOME:-}"; fi
if [ -n "$JAVA8_HOME_RESOLVED" ]; then
    JAVAC="$JAVA8_HOME_RESOLVED/bin/javac"
    COMPILER="javac"
else
    ECJ_JAR=$(resolve_ecj_jar || true)
    JAVA_CMD="${JAVAP_HOME:+$JAVAP_HOME/bin/}java"
    if [ -z "$ECJ_JAR" ] || ! command -v "$JAVA_CMD" >/dev/null 2>&1; then
        echo "错误: 未找到可用的 Java 8 javac 或 ECJ 编译器。" >&2
        exit 1
    fi
    COMPILER="ecj"
fi
# Java 8 javap 在较大的 class verbose 输出接入提前退出的管道时可能持续阻塞；
# 审计工具使用当前 JDK，字节码目标版本仍由 Java 8 javac/ECJ 决定。
JAVAP="${JAVAP_HOME:+$JAVAP_HOME/bin/}javap"

echo "=== 编译 component 类 (-source 1.6 -target 1.6) ==="
if [ "$COMPILER" = "javac" ]; then
    echo "编译器: $($JAVAC -version 2>&1)"
else
    echo "编译器: ECJ $(basename "$ECJ_JAR")"
fi
echo "源码目录: $SRC_DIR"
echo "输出目录: $OUT_DIR"
echo "临时目录: $TMP_DIR"
echo ""

# Component 彼此独立，逐文件编译可限制 javac 峰值内存，并同步验证每个源码
# 不依赖同目录中的其他 Component。
for sourcefile in "$SRC_DIR"/*.java; do
    if [ "$COMPILER" = "javac" ]; then
        "$JAVAC" -J-Xms16m -J-Xmx128m -source 1.6 -target 1.6 \
              -Xlint:-options -d "$TMP_DIR" "$sourcefile"
    else
        if [ -f "$JAVA8_API_HOME/jre/lib/rt.jar" ]; then
            "$JAVA_CMD" -Xms16m -Xmx128m -jar "$ECJ_JAR" \
                  -1.6 -proc:none -nowarn \
                  -bootclasspath "$JAVA8_API_HOME/jre/lib/rt.jar" \
                  -d "$TMP_DIR" "$sourcefile"
        else
            "$JAVA_CMD" -Xms16m -Xmx128m -jar "$ECJ_JAR" \
                  -1.6 -proc:none -nowarn -d "$TMP_DIR" "$sourcefile"
        fi
    fi
done

CLASS_DIR="$TMP_DIR/org/leo/core/component"
source_count=$(find "$SRC_DIR" -maxdepth 1 -type f -name '*.java' | wc -l | tr -d ' ')
class_count=$(find "$CLASS_DIR" -maxdepth 1 -type f -name '*.class' | wc -l | tr -d ' ')
if [ "$class_count" -ne "$source_count" ]; then
    echo "错误: 源文件数为 $source_count，但生成了 $class_count 个 class；请检查内部类或遗漏。" >&2
    exit 1
fi
if find "$CLASS_DIR" -maxdepth 1 -type f -name '*$*.class' | grep -q .; then
    echo "错误: 检测到额外的内部类 class，单文件 payload 无法加载。" >&2
    find "$CLASS_DIR" -maxdepth 1 -type f -name '*$*.class' -print >&2
    exit 1
fi

for classfile in "$CLASS_DIR"/*.class; do
    major=$($JAVAP -verbose "$classfile" | awk '/major version:/{print $3; exit}')
    if [ "$major" != "50" ]; then
        echo "错误: $(basename "$classfile") 的字节码版本为 $major，期望 50 (Java 6)。" >&2
        exit 1
    fi
    class_name=$(basename "$classfile" .class)
    allowed_name="org/leo/core/component/$class_name"
    foreign_refs=$($JAVAP -verbose "$classfile" \
        | sed -n 's|.*// \(org/leo/core/component/[A-Za-z0-9_$]*\).*|\1|p' \
        | sort -u \
        | grep -v "^${allowed_name}$" || true)
    if [ -n "$foreign_refs" ]; then
        echo "错误: $class_name 直接引用了其他 Component，单 class payload 无法独立加载:" >&2
        echo "$foreign_refs" >&2
        exit 1
    fi
done

echo "=== 检查 Java 6 API 兼容性 ==="
"$REPO_DIR/mvnw" -q -f "$AUDIT_POM" \
    -Dcomponent.classes.dir="$TMP_DIR" \
    org.codehaus.mojo:animal-sniffer-maven-plugin:1.27:check

if [ "$MODE" = "--check" ]; then
    echo "=== 检查已提交 payload 与源码一致性 ==="
    payload_count=$(find "$OUT_DIR" -maxdepth 1 -type f -name '*.payload' | wc -l | tr -d ' ')
    if [ "$payload_count" -ne "$source_count" ]; then
        echo "错误: 源文件数为 ${source_count}，但 resources 中有 ${payload_count} 个 payload。" >&2
        exit 1
    fi

    for sourcefile in "$SRC_DIR"/*.java; do
        class_name=$(basename "$sourcefile" .java)
        generated="$CLASS_DIR/${class_name}.class"
        committed="$OUT_DIR/${class_name}.payload"
        if [ ! -f "$committed" ]; then
            echo "错误: 缺少已提交 payload: $committed" >&2
            exit 1
        fi
        if ! cmp -s "$generated" "$committed"; then
            echo "错误: ${class_name}.payload 与当前源码不一致；请运行 bash compile-components.sh 更新。" >&2
            exit 1
        fi
    done

    for payload in "$OUT_DIR"/*.payload; do
        class_name=$(basename "$payload" .payload)
        if [ ! -f "$SRC_DIR/${class_name}.java" ]; then
            echo "错误: 检测到没有对应源码的 payload: $payload" >&2
            exit 1
        fi
    done

    echo ""
    echo "=== 检查完成: ${class_count} 个 Component 均通过且 payload 与源码一致 ==="
    exit 0
fi

echo "=== 拷贝 .payload 文件 ==="
mkdir -p "$OUT_DIR"

count=0
for classfile in "$CLASS_DIR"/*.class; do
    filename=$(basename "$classfile" .class)
    cp "$classfile" "$OUT_DIR/${filename}.payload"
    echo "  $filename.payload"
    count=$((count + 1))
done

echo ""
echo "=== 完成: ${count} 个 .payload 已更新到 $OUT_DIR ==="
