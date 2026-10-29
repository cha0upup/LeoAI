package org.leo.web.init;

import org.leo.service.user.SystemInitializationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Announces web setup after all startup prerequisites have completed. */
@Component
public class DataInitializer {
    private static final Logger logger = LoggerFactory.getLogger(DataInitializer.class);
    private final SystemInitializationService initialization;

    public DataInitializer(SystemInitializationService initialization) {
        this.initialization = initialization;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        if (initialization.isInitializationRequired()) {
            logger.info("系统尚未初始化，请打开 Web 页面设置 admin 密码");
        }
    }
}
