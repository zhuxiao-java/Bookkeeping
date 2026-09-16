package com.bookkeeping.service;

/**
 * 同步任务
 * @author zx
 */
public interface TaskService {

    void dailyCheckIn();

    void monthlyLevelChange();

    void dailyWeather();

    void dailyGreeting();
}
