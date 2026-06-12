package org.leo.web.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * SPA 路由转发：将非 API、非静态资源的请求转发到 index.html，
 * 由前端 Vue Router 处理客户端路由。
 * <p>
 * 只匹配不含 "." 的路径（排除 .html/.js/.css/.ico 等静态资源），
 * 同时排除 /platform 前缀的 API 路径。
 */
@Controller
public class SpaForwardController {

    @RequestMapping(value = {
            "/{path:[^\\.]*}",
            "/{path:[^\\.]*}/{sub:[^\\.]*}",
            "/{path:[^\\.]*}/{sub:[^\\.]*}/{rest:[^\\.]*}"
    })
    public String forward() {
        return "forward:/index.html";
    }
}
