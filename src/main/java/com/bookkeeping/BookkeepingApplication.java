package com.bookkeeping;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 启动类
 * @author zhuxiao
 */
@SpringBootApplication
@MapperScan("com.bookkeeping.dao.mapper")
@EnableScheduling
public class BookkeepingApplication {
    public static void main(String[] args) {
        SpringApplication.run(BookkeepingApplication.class, args);
    }
}
