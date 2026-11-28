package org.leo.ai.service.skill;

import java.util.Arrays;

/** Shared delimiter handling for validating and reading a SKILL.md. */
record SkillFrontmatter(String[] lines, int closingLine) {

    static SkillFrontmatter split(String content) {
        if (content == null || !content.startsWith("---")) return null;
        String[] lines = content.split("\n", -1);
        int closingLine = 1;
        while (closingLine < lines.length && !lines[closingLine].trim().equals("---")) closingLine++;
        return new SkillFrontmatter(lines, closingLine);
    }

    boolean hasClosingDelimiter() {
        return closingLine < lines.length;
    }

    String yaml() {
        return joinLines(1, closingLine);
    }

    String body() {
        int start = closingLine + 1;
        while (start < lines.length && lines[start].isBlank()) start++;
        return joinLines(start, lines.length);
    }

    private String joinLines(int from, int to) {
        return String.join("\n", Arrays.copyOfRange(lines, from, to));
    }
}
