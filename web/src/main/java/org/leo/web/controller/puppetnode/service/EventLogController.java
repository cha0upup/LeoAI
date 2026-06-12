package org.leo.web.controller.puppetnode.service;

import org.leo.core.puppet.impl.JavaPuppetNode;
import org.leo.core.util.ApiResponse;
import org.leo.web.util.ControllerUtil;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 操作系统日志管理控制器
 */
@RestController
@RequestMapping("/puppet-node/event-log")
public class EventLogController {

    /** SSE 跟随用的定时调度池(全控制器共享,daemon 线程) */
    private static final ScheduledExecutorService FOLLOW_SCHEDULER = Executors.newScheduledThreadPool(4, r -> {
        Thread t = new Thread(r, "event-log-follow");
        t.setDaemon(true);
        return t;
    });

    @RequestMapping(value = "/list-sources", method = RequestMethod.POST)
    public HashMap<String, Object> listSources(@RequestBody HashMap<String, Object> params) {
        return ControllerUtil.handlePuppetCall(params, "获取日志源列表失败",
                node -> node.listEventLogSources());
    }

    @RequestMapping(value = "/query", method = RequestMethod.POST)
    public HashMap<String, Object> query(@RequestBody HashMap<String, Object> params) {
        return ControllerUtil.handlePuppetCall(params, "查询日志失败", node -> node.queryEventLog(
                ControllerUtil.getStr(params, "source"),
                ControllerUtil.getInt(params, "maxEntries", 50),
                ControllerUtil.getStr(params, "keyword"),
                ControllerUtil.getStr(params, "level"),
                ControllerUtil.getStr(params, "since"),
                ControllerUtil.getStr(params, "until"),
                ControllerUtil.getStr(params, "eventId"),
                ControllerUtil.getStr(params, "format"),
                ControllerUtil.getInt(params, "maxBytes", 0),
                ControllerUtil.getLongOrNull(params, "cursor"),
                ControllerUtil.getStr(params, "direction"),
                ControllerUtil.getIntOrNull(params, "minStatus"),
                ControllerUtil.getIntOrNull(params, "maxStatus"),
                ControllerUtil.getStr(params, "ipPrefix"),
                ControllerUtil.getStr(params, "pathPrefix")));
    }

    @RequestMapping(value = "/aggregate", method = RequestMethod.POST)
    public HashMap<String, Object> aggregate(@RequestBody HashMap<String, Object> params) {
        if (ControllerUtil.getStr(params, "source") == null) {
            return ApiResponse.badRequest("source 参数必填(应用日志文件路径)");
        }
        return ControllerUtil.handlePuppetCall(params, "聚合日志失败", node -> node.aggregateEventLog(
                ControllerUtil.getStr(params, "source"),
                ControllerUtil.getStr(params, "format"),
                ControllerUtil.getStr(params, "groupBy"),
                ControllerUtil.getInt(params, "topN", 20),
                ControllerUtil.getInt(params, "maxScan", 50000),
                ControllerUtil.getInt(params, "maxBytes", 0),
                ControllerUtil.getStr(params, "keyword"),
                ControllerUtil.getIntOrNull(params, "minStatus"),
                ControllerUtil.getIntOrNull(params, "maxStatus"),
                ControllerUtil.getStr(params, "ipPrefix"),
                ControllerUtil.getStr(params, "pathPrefix"),
                ControllerUtil.getBool(params, "slow")));
    }

    @RequestMapping(value = "/meta", method = RequestMethod.POST)
    public HashMap<String, Object> meta(@RequestBody HashMap<String, Object> params) {
        if (ControllerUtil.getStr(params, "source") == null) {
            return ApiResponse.badRequest("source 参数必填(文件路径)");
        }
        return ControllerUtil.handlePuppetCall(params, "获取元数据失败", node -> node.metaEventLog(
                ControllerUtil.getStr(params, "source"),
                ControllerUtil.getStr(params, "format")));
    }

    @RequestMapping(value = "/stats", method = RequestMethod.POST)
    public HashMap<String, Object> stats(@RequestBody HashMap<String, Object> params) {
        return ControllerUtil.handlePuppetCall(params, "获取日志统计失败",
                node -> node.getEventLogStats(ControllerUtil.getStr(params, "source")));
    }

    @RequestMapping(value = "/clear", method = RequestMethod.POST)
    public HashMap<String, Object> clear(@RequestBody HashMap<String, Object> params) {
        if (ControllerUtil.getStr(params, "source") == null) {
            return ApiResponse.badRequest("source 参数必填");
        }
        return ControllerUtil.handlePuppetCall(params, "清除日志失败",
                node -> node.clearEventLog(ControllerUtil.getStr(params, "source")));
    }

    /**
     * 实时跟随(SSE): 服务端轮询 puppet 的 query(direction=newer),把新增行推送给前端。
     * 客户端 EventSource 连上后,每 ~1.5s 拉一次增量。
     * 关闭/超时自动清理 schedule task。
     */
    @RequestMapping(value = "/follow", method = RequestMethod.GET)
    public SseEmitter follow(@RequestParam("sessionId") String sessionId,
                             @RequestParam("source") String source,
                             @RequestParam(value = "format", required = false) String format,
                             @RequestParam(value = "intervalMs", required = false) Long intervalMs) {
        // 超时 30 分钟自动结束(避免连接泄露)
        final SseEmitter emitter = new SseEmitter(30L * 60L * 1000L);
        if (source == null || !source.startsWith("/")) {
            try { emitter.send(SseEmitter.event().name("error").data("source 必须为文件路径")); } catch (Exception ignored) {}
            emitter.complete();
            return emitter;
        }
        long period = (intervalMs == null || intervalMs.longValue() < 500L) ? 1500L : intervalMs.longValue();

        final HashMap<String, Object> baseParams = new HashMap<>();
        baseParams.put("sessionId", sessionId);
        final JavaPuppetNode node;
        try {
            node = ControllerUtil.getPuppetNode(baseParams);
        } catch (Exception e) {
            try { emitter.send(SseEmitter.event().name("error").data(e.getMessage())); } catch (Exception ignored) {}
            emitter.complete();
            return emitter;
        }
        final AtomicLong cursor = new AtomicLong(-1L); // 第一次拿 meta 得到 fileSize 作为起点
        final ScheduledFuture<?>[] holder = new ScheduledFuture<?>[1];

        Runnable poller = () -> {
            try {
                if (cursor.get() < 0) {
                    Map<String, Object> initialMeta = node.metaEventLog(source, format);
                    if (initialMeta != null && Integer.valueOf(200).equals(initialMeta.get("code"))) {
                        Object sz = initialMeta.get("size");
                        if (sz instanceof Number) cursor.set(((Number) sz).longValue());
                        emitter.send(SseEmitter.event().name("meta").data(initialMeta));
                    } else {
                        emitter.send(SseEmitter.event().name("error").data(
                                initialMeta == null ? "meta failed" : String.valueOf(initialMeta.get("msg"))));
                        emitter.complete();
                        if (holder[0] != null) holder[0].cancel(false);
                        return;
                    }
                }
                Map<String, Object> result = node.queryEventLog(source, 500, null, null, null, null, null,
                        format, 0, Long.valueOf(cursor.get()), "newer", null, null, null, null);
                if (result == null) return;
                Object metaObj = result.get("meta");
                if (metaObj instanceof Map) {
                    Object endByteObj = ((Map<?, ?>) metaObj).get("endByte");
                    if (endByteObj instanceof Number) cursor.set(((Number) endByteObj).longValue());
                }
                Object entries = result.get("entries");
                if (entries instanceof List && !((List<?>) entries).isEmpty()) {
                    emitter.send(SseEmitter.event().name("append").data(result));
                }
            } catch (org.springframework.web.context.request.async.AsyncRequestNotUsableException disconnect) {
                if (holder[0] != null) holder[0].cancel(false);
                try { emitter.complete(); } catch (Exception ignored) {}
            } catch (Exception ex) {
                try {
                    emitter.send(SseEmitter.event().name("error").data(ex.getClass().getSimpleName() + ": " + ex.getMessage()));
                } catch (Exception ignored) {}
            }
        };

        holder[0] = FOLLOW_SCHEDULER.scheduleWithFixedDelay(poller, 0L, period, TimeUnit.MILLISECONDS);

        Runnable cancel = () -> {
            if (holder[0] != null) holder[0].cancel(false);
        };
        emitter.onCompletion(cancel);
        emitter.onTimeout(cancel);
        emitter.onError(t -> {
            if (holder[0] != null) holder[0].cancel(false);
        });
        return emitter;
    }
}
