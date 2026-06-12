package org.leo.core.puppet.service;

import org.leo.core.net.Communication;
import org.leo.core.net.layer.RequestLayer;
import org.leo.core.net.layer.ResponseLayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SUID / SGID / Capabilities 枚举服务（服务器端解析版）。
 * 仅 Linux 有效，用于提权路径分析。
 */
public class SuidCapabilityService extends ComponentService {

    private static final String GTFOBINS =
            ",aria2c,ash,awk,base64,bash,busybox,cat,chmod,chown,cp,csh,curl,cut,dash,date,"
            + "dd,diff,dmsetup,docker,ed,emacs,env,expand,expect,find,flock,fmt,fold,gdb,"
            + "gimp,grep,head,ionice,ip,jjs,jq,ksh,ld.so,less,logsave,lua,make,man,mawk,"
            + "more,mv,mysql,nano,nawk,nc,nice,nl,nmap,node,nohup,od,openssl,perl,pg,php,"
            + "pic,pico,pip,python,python2,python3,readelf,rev,rlwrap,rpm,rpmquery,rsync,"
            + "ruby,run-parts,rvim,scp,sed,setarch,sftp,shuf,socat,sort,sqlite3,ssh,stdbuf,"
            + "strace,strings,su,sysctl,tail,tar,taskset,tclsh,tee,time,timeout,ul,unexpand,"
            + "uniq,unshare,vi,vim,watch,wget,wish,xargs,xxd,zip,zsh,";

    public SuidCapabilityService(Communication communication,
                                 List<RequestLayer> requestLayers,
                                 List<ResponseLayer> responseLayers) {
        super(communication, requestLayers, responseLayers);
    }

    public Map<String, Object> listSuid() throws Exception {
        return enumerate(0);
    }

    public Map<String, Object> listSgid() throws Exception {
        return enumerate(1);
    }

    public Map<String, Object> listCapabilities() throws Exception {
        return enumerate(2);
    }

    public Map<String, Object> listAll() throws Exception {
        return enumerate(3);
    }

    // ── 核心枚举逻辑 ──────────────────────────────────────────────────────────

    private Map<String, Object> enumerate(int op) throws Exception {
        Map<String, Object> data = new HashMap<>();
        data.put("os", "linux");

        if (op == 0 || op == 3) data.put("suid", collectSuid());
        if (op == 1 || op == 3) data.put("sgid", collectSgid());
        if (op == 2 || op == 3) data.put("capabilities", collectCapabilities());

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", data);
        return result;
    }

    private Map<String, Object> collectSuid() throws Exception {
        String output = execWithTimeout("find / -perm -4000 -type f 2>/dev/null | head -200", 60);
        List<Map<String, Object>> files = new ArrayList<>();
        List<String> exploitable = new ArrayList<>();

        for (String line : lines(output)) {
            String path = line.trim();
            if (path.isEmpty() || path.startsWith("[WARN")) continue;
            String name = extractName(path);
            boolean isGtfo = isGtfoBin(name);
            Map<String, Object> entry = new HashMap<>();
            entry.put("path", path);
            entry.put("name", name);
            entry.put("gtfobins", isGtfo);
            files.add(entry);
            if (isGtfo) exploitable.add(path);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("files", files);
        result.put("total", files.size());
        result.put("exploitable", exploitable);
        result.put("exploitableCount", exploitable.size());
        return result;
    }

    private Map<String, Object> collectSgid() throws Exception {
        String output = execWithTimeout("find / -perm -2000 -type f 2>/dev/null | head -200", 60);
        List<Map<String, Object>> files = new ArrayList<>();

        for (String line : lines(output)) {
            String path = line.trim();
            if (path.isEmpty() || path.startsWith("[WARN")) continue;
            String name = extractName(path);
            Map<String, Object> entry = new HashMap<>();
            entry.put("path", path);
            entry.put("name", name);
            entry.put("gtfobins", isGtfoBin(name));
            files.add(entry);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("files", files);
        result.put("total", files.size());
        return result;
    }

    private Map<String, Object> collectCapabilities() throws Exception {
        String output = execWithTimeout("getcap -r / 2>/dev/null | head -200", 60);
        List<Map<String, Object>> files = new ArrayList<>();
        List<String> dangerous = new ArrayList<>();

        for (String line : lines(output)) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("[WARN")) continue;

            int eqIdx = line.indexOf(" = ");
            if (eqIdx < 0) eqIdx = line.indexOf(" cap_");

            Map<String, Object> entry = new HashMap<>();
            if (eqIdx > 0) {
                String path = line.substring(0, eqIdx).trim();
                String caps = line.substring(eqIdx).trim();
                if (caps.startsWith("= ")) caps = caps.substring(2);
                String name = extractName(path);
                boolean isDangerous = isDangerousCapability(caps);
                entry.put("path", path);
                entry.put("name", name);
                entry.put("capabilities", caps);
                entry.put("gtfobins", isGtfoBin(name));
                entry.put("dangerous", isDangerous);
                if (isDangerous) dangerous.add(path + " " + caps);
            } else {
                entry.put("raw", line);
            }
            files.add(entry);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("files", files);
        result.put("total", files.size());
        result.put("dangerous", dangerous);
        result.put("dangerousCount", dangerous.size());
        return result;
    }

    private boolean isDangerousCapability(String caps) {
        return caps.contains("cap_setuid") || caps.contains("cap_setgid")
                || caps.contains("cap_dac_override") || caps.contains("cap_dac_read_search")
                || caps.contains("cap_sys_admin") || caps.contains("cap_sys_ptrace")
                || caps.contains("cap_net_raw") || caps.contains("cap_net_bind_service");
    }

    // ── 辅助方法 ─────────────────────────────────────────────────────────────

    private boolean isGtfoBin(String name) {
        return name != null && !name.isEmpty() && GTFOBINS.contains("," + name + ",");
    }

    private String extractName(String path) {
        if (path == null) return "";
        int idx = path.lastIndexOf('/');
        return idx >= 0 ? path.substring(idx + 1) : path;
    }

    private String[] lines(String s) {
        if (s == null || s.isEmpty()) return new String[0];
        return s.split("\n");
    }
}
