package com.jsnx.edsp.plan;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Validates the deliberately narrow SQL contract used by database resources. */
public final class SqlQuerySupport {
    private static final Pattern PARAMETER = Pattern.compile("(?<!:):([A-Za-z_][A-Za-z0-9_]*)");
    private static final Set<String> BLOCKED_WORDS = Set.of(
            "INSERT", "UPDATE", "DELETE", "MERGE", "UPSERT", "REPLACE", "DROP", "ALTER", "CREATE",
            "TRUNCATE", "GRANT", "REVOKE", "CALL", "EXEC", "EXECUTE", "COMMIT", "ROLLBACK",
            "BEGIN", "DECLARE", "COPY", "LOAD", "INTO", "OUTFILE", "DUMPFILE", "ATTACH", "DETACH");

    private SqlQuerySupport() {}

    public static Set<String> validateAndFindParameters(String sql) {
        if (sql == null || sql.isBlank()) throw new IllegalArgumentException("请输入 SQL 查询语句");
        if (sql.length() > 16_000) throw new IllegalArgumentException("SQL 长度不能超过 16000 个字符");
        String visible = maskLiteralsAndComments(sql);
        String normalized = visible.stripLeading().toUpperCase(Locale.ROOT);
        if (!(normalized.startsWith("SELECT ") || normalized.startsWith("SELECT\n")
                || normalized.startsWith("SELECT\t") || normalized.startsWith("WITH ")
                || normalized.startsWith("WITH\n") || normalized.startsWith("WITH\t"))) {
            throw new IllegalArgumentException("仅允许 SELECT 或 WITH 查询语句");
        }
        if (visible.indexOf(';') >= 0) throw new IllegalArgumentException("不允许在 SQL 中执行多条语句");
        Matcher words = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*").matcher(visible);
        while (words.find()) {
            String word = words.group().toUpperCase(Locale.ROOT);
            if (BLOCKED_WORDS.contains(word)) throw new IllegalArgumentException("只读查询中不允许使用关键字：" + word);
        }
        Matcher parameters = PARAMETER.matcher(visible);
        Set<String> names = new LinkedHashSet<>();
        while (parameters.find()) names.add(parameters.group(1));
        return Set.copyOf(names);
    }

    private static String maskLiteralsAndComments(String sql) {
        StringBuilder visible = new StringBuilder(sql.length());
        int state = 0;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';
            if (state == 0) {
                if (c == '\'' || c == '"' || c == '`') { state = c; visible.append(' '); }
                else if (c == '-' && next == '-') { state = 4; visible.append("  "); i++; }
                else if (c == '/' && next == '*') { state = 5; visible.append("  "); i++; }
                else visible.append(c);
            } else if (state == 4) {
                visible.append(c == '\n' ? '\n' : ' ');
                if (c == '\n') state = 0;
            } else if (state == 5) {
                if (c == '*' && next == '/') { visible.append("  "); i++; state = 0; }
                else visible.append(' ');
            } else {
                visible.append(' ');
                if (c == state) {
                    if (next == state && state != '`') { visible.append(' '); i++; }
                    else state = 0;
                } else if (c == '\\' && i + 1 < sql.length()) { visible.append(' '); i++; }
            }
        }
        if (state != 0 && state != 4) throw new IllegalArgumentException("SQL 字符串或注释未闭合");
        return visible.toString();
    }
}
