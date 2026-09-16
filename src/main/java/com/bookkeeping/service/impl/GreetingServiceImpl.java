package com.bookkeeping.service.impl;

import com.bookkeeping.constant.GreetingCard;
import com.bookkeeping.constant.MessageType;
import com.bookkeeping.dao.dto.UserLevelDTO;
import com.bookkeeping.service.GreetingService;
import com.bookkeeping.service.MessageService;
import com.bookkeeping.service.UserLevelService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 贺卡生产服务实现：固定节日 + 用户生日，每日检查推送（P1-3）。
 *
 * @author zhuxiao
 */
@Service
public class GreetingServiceImpl implements GreetingService {

    @Resource
    private MessageService messageService;
    @Resource
    private UserLevelService userLevelService;

    /** 公历固定节日（MM-dd → 节日名）；农历节日日期浮动，暂不纳入。 */
    private static final Map<String, String> FESTIVALS = new LinkedHashMap<>();

    static {
        FESTIVALS.put("01-01", "元旦");
        FESTIVALS.put("02-14", "情人节");
        FESTIVALS.put("03-08", "妇女节");
        FESTIVALS.put("05-01", "劳动节");
        FESTIVALS.put("06-01", "儿童节");
        FESTIVALS.put("09-10", "教师节");
        FESTIVALS.put("10-01", "国庆节");
        FESTIVALS.put("12-24", "平安夜");
        FESTIVALS.put("12-25", "圣诞节");
    }

    @Override
    public void dailyGreeting() {
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        LocalDateTime start = today.atStartOfDay();
        LocalDateTime end = today.plusDays(1).atStartOfDay();
        pushFestival(today, start, end);
        pushBirthday(today, start, end);
    }

    /** 启动即检查一次，避免当天开机晚于调度点时漏发（同日同类已由 exists 去重）。 */
    @PostConstruct
    public void init() {
        dailyGreeting();
    }

    private void pushFestival(LocalDate today, LocalDateTime start, LocalDateTime end) {
        String key = String.format("%02d-%02d", today.getMonthValue(), today.getDayOfMonth());
        String name = FESTIVALS.get(key);
        if (Objects.isNull(name)) {
            return;
        }
        String title = name + "快乐";
        if (messageService.exists(MessageType.GREETING, title, start, end)) {
            return;
        }
        String content = String.format("今天是%s，愿你被温柔以待，也愿你的账本越来越健康～", name);
        messageService.pushMessage(title, content, null, MessageType.GREETING, null, GreetingCard.FESTIVAL);
    }

    private void pushBirthday(LocalDate today, LocalDateTime start, LocalDateTime end) {
        UserLevelDTO user = userLevelService.selectUserLevel();
        if (Objects.isNull(user) || !isBirthdayToday(user.getBirthday(), today)) {
            return;
        }
        String title = "生日快乐";
        if (messageService.exists(MessageType.GREETING, title, start, end)) {
            return;
        }
        String content = "🎂 生日快乐！\n愿新的一岁所求皆如愿，所行化坦途。\n也谢谢你一直用这本账记录生活～";
        messageService.pushMessage(title, content, null, MessageType.GREETING, null, GreetingCard.BIRTHDAY);
    }

    /** 生日支持 yyyy-MM-dd 或 MM-dd，仅比对月与日。 */
    private boolean isBirthdayToday(String birthday, LocalDate today) {
        if (StringUtils.isBlank(birthday)) {
            return false;
        }
        String[] parts = birthday.trim().split("-");
        try {
            int month;
            int day;
            if (parts.length == 3) {
                month = Integer.parseInt(parts[1]);
                day = Integer.parseInt(parts[2]);
            } else if (parts.length == 2) {
                month = Integer.parseInt(parts[0]);
                day = Integer.parseInt(parts[1]);
            } else {
                return false;
            }
            return month == today.getMonthValue() && day == today.getDayOfMonth();
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
