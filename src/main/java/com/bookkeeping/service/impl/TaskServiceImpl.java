package com.bookkeeping.service.impl;

import com.bookkeeping.service.CheckInService;
import com.bookkeeping.service.GreetingService;
import com.bookkeeping.service.TaskService;
import com.bookkeeping.service.UserLevelService;
import com.bookkeeping.service.WeatherService;
import jakarta.annotation.Resource;
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
