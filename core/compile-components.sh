#!/bin/bash
# 用 Java 8 的 javac 以 -source 1.6 -target 1.6 编译所有 component 类
# 生成 major version 50 (Java 6) 的 .payload 放入 src/main/resources/component/
#
# 用法: cd LeoAI/core && bash compile-components.sh
#
# 要求 PATH 中 javac 为 Java 8（Zulu 8 / OpenJDK 8 等）
# -source 1.6 限制语言特性（无 lambda/diamond/try-with-resources）
# -target 1.6 生成 major version 50 字节码
# CloneWithJavassist.setVersionToJava5() 运行时会进一步将版本号降为 49

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC_DIR="$SCRIPT_DIR/src/main/java/org/leo/core/component"
OUT_DIR="$SCRIPT_DIR/src/main/resources/component"
TMP_DIR=$(mktemp -d)

echo "=== 编译 component 类 (-source 1.6 -target 1.6) ==="
echo "javac: $(javac -version 2>&1)"
echo "源码目录: $SRC_DIR"
echo "输出目录: $OUT_DIR"
echo "临时目录: $TMP_DIR"
echo ""

javac -source 1.6 -target 1.6 \
      -Xlint:-options \
      -d "$TMP_DIR" \
      "$SRC_DIR"/*.java

echo "=== 拷贝 .payload 文件 ==="
mkdir -p "$OUT_DIR"

count=0
for classfile in "$TMP_DIR/org/leo/core/component"/*.class; do
    filename=$(basename "$classfile" .class)
    cp "$classfile" "$OUT_DIR/${filename}.payload"
    echo "  $filename.payload"
    count=$((count + 1))
done

rm -rf "$TMP_DIR"

echo ""
echo "=== 完成: ${count} 个 .payload 已更新到 $OUT_DIR ==="
