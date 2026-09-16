package com.bookkeeping.service;

/**
 * 贺卡生产服务：负责固定节日与用户生日的每日贺卡推送（P1-3）。
 * 等级里程碑贺卡由 UserLevelServiceImpl 在升级时直接推送，不在此列。
 *
 * @author zhuxiao
 */
public interface GreetingService {

    /**
     * 每日检查：命中固定节日或用户生日则推送对应贺卡（同日同类只推一次）。
     */
    void dailyGreeting();
}
