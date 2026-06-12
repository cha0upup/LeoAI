package org.leo.web;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;


/**
 * LeoSpring 主应用类
 * 启用定时任务功能，支持日志清理等后台任务
 *
 * @author LeoSpring
 * @version 2.1
 */

@MapperScan("org.leo.dao.mapper")
@SpringBootApplication(scanBasePackages = {"org.leo"})
@EnableScheduling
public class LeoApplication {

    public static void main(String[] args) {
        SpringApplication.run(LeoApplication.class, args);
    }
}
