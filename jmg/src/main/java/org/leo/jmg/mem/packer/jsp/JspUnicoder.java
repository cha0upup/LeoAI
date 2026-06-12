package org.leo.jmg.mem.packer.jsp;

import java.util.concurrent.ThreadLocalRandom;

public class JspUnicoder {

    public static String encode(String content, boolean isJsp) {
        if (content == null) {
            return null;
        }
        StringBuilder result = new StringBuilder(content.length());
        int lineStart = 0;
        int length = content.length();
        for (int i = 0; i < length; i++) {
            if (content.charAt(i) == '\n') {
                appendEncodedLine(result, content.substring(lineStart, i), isJsp);
                result.append('\n');
                lineStart = i + 1;
            }
        }
        if (lineStart <= length) {
            appendEncodedLine(result, content.substring(lineStart), isJsp);
        }
        return result.toString();
    }

    private static void appendEncodedLine(StringBuilder output, String line, boolean isJsp) {
        if (shouldSkipLine(line, isJsp)) {
            output.append(line);
            return;
        }
        if (line.contains("page import") || line.contains("page pageEncoding") || line.contains("page contentType")) {
            int firstQuote = line.indexOf('"');
            int lastQuote = line.lastIndexOf('"');
            if (firstQuote != -1 && lastQuote > firstQuote) {
                String oldStr = line.substring(firstQuote + 1, lastQuote);
                String encoded = encodeWordChars(oldStr);
                output.append(line, 0, firstQuote + 1)
                        .append(encoded)
                        .append(line.substring(lastQuote));
                return;
            }
        }
        output.append(encodeWordChars(line));
    }

    private static boolean shouldSkipLine(String line, boolean isJsp) {
        if (line == null) {
            return false;
        }
        if (!isJsp && (line.contains("jsp:root")
                || line.contains("jsp:declaration")
                || line.contains("jsp:scriptlet")
                || line.contains("jsp:directive.page"))) {
            return true;
        }
        return false;
    }

    private static String encodeWordChars(String input) {
        StringBuilder encoded = new StringBuilder(input.length() * 4);
        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            if (isWordChar(ch)) {
                encoded.append(toUnicodeEscape(ch));
            } else {
                encoded.append(ch);
            }
        }
        return encoded.toString();
    }

    /**
     * 仅对 ASCII 范围（< 0x80）的字母、数字和下划线做 Unicode 转义。
     * CJK 等宽字符不在此范围内，GHOST_BITS_ENCODE 生成的汉字字面量会被安全跳过。
     */
    private static boolean isWordChar(char ch) {
        return ch < 0x80 && (Character.isLetterOrDigit(ch) || ch == '_');
    }

    /**
     * 将单个 ASCII 字符转为 Unicode 转义形式（反斜杠后跟 1-4 个字母 u，再跟 4 位十六进制）。
     * JSP 规范允许在字母 u 前使用多个重复 u（1 到 4 个均合法），编译器将其视为等价形式。
     */
    private static String toUnicodeEscape(char ch) {
        // ch 保证是 ASCII（< 0x80），直接格式化为 4 位十六进制
        int uCount = ThreadLocalRandom.current().nextInt(1, 5);
        String uPrefix = "\\u" + "uuu".substring(0, uCount - 1);
        return String.format("%s%04x", uPrefix, (int) ch);
    }
}
