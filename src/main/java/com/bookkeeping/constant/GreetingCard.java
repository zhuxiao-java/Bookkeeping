package com.bookkeeping.constant;

/**
 * 贺卡封面图路径常量。
 * <p>
 * 值为前端可直接用作 {@code <img src>} 的根相对路径，对应 frontend/public/greeting/ 下的内置 SVG 预设
 * （public 在 Vite dev 与 Electron 生产环境均挂载在应用根路径）。封面缺失时前端 MessageDetailDialog
 * 会静默降级为纯渐变卡片，故此处仅约定路径、不校验文件存在。
 *
 * @author zhuxiao
 */
public final class GreetingCard {

    /** 生日贺卡封面 */
    public static final String BIRTHDAY = "/greeting/birthday.svg";
    /** 节日贺卡封面 */
    public static final String FESTIVAL = "/greeting/festival.svg";
    /** 等级里程碑贺卡封面 */
    public static final String MILESTONE = "/greeting/milestone.svg";

    private GreetingCard() {
    }
}
