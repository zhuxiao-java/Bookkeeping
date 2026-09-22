package com.bookkeeping.service.impl;

import com.bookkeeping.constant.ExpTransactionType;
import com.bookkeeping.service.CheckInService;
import com.bookkeeping.service.GreetingService;
import com.bookkeeping.service.TaskService;
import com.bookkeeping.service.UserLevelService;
import com.bookkeeping.service.WeatherService;
import jakarta.annotation.Resource;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 任务服务实现类
 * @author zhuxiao
 */
@Service
public class TaskServiceImpl implements TaskService {
    @Resource
    private CheckInService checkInService;
    @Resource
    private UserLevelService levelService;
    @Resource
    private WeatherService weatherService;
    @Resource
    private GreetingService greetingService;

    /** 代理就绪后执行，保证启动奖励也受事务保护；先结算再签到。 */
    @EventListener(ApplicationReadyEvent.class)
    public void initializeLevels() {
        levelService.gainExperience(ExpTransactionType.OTHER, 0, "等级校准", 0);
        levelService.monthlyLevelChange();
        checkInService.dailyCheckIn();
    }

    @Override
    @Scheduled(cron = "0 0 9,14,18,22 * * ?")
    public void dailyCheckIn() {
        checkInService.dailyCheckIn();
    }

    @Override
    @Scheduled(cron = "0 0 9,14,18,22 * * ?")
    public void monthlyLevelChange() {
        levelService.monthlyLevelChange();
    }

    @Override
    public void dailyWeather() {
        weatherService.dailyWeather();
    }

    @Override
    @Scheduled(cron = "0 0 9 * * ?")
    public void dailyGreeting() {
        greetingService.dailyGreeting();
    }
}
