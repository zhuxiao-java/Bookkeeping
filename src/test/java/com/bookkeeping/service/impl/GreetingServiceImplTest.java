package com.bookkeeping.service.impl;

import com.bookkeeping.constant.GreetingCard;
import com.bookkeeping.constant.MessageType;
import com.bookkeeping.dao.dto.UserLevelDTO;
import com.bookkeeping.service.MessageService;
import com.bookkeeping.service.UserLevelService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 覆盖 P1-3：生日贺卡生产。生日取当天，故与运行日期无关；
 * 校验 yyyy-MM-dd / MM-dd 两种格式解析、命中推送 BIRTHDAY 贺卡、同日去重、无生日不推送。
 */
class GreetingServiceImplTest {

    private MessageService messageService;
    private UserLevelService userLevelService;
    private GreetingServiceImpl service;

    @BeforeEach
    void setUp() {
        messageService = mock(MessageService.class);
        userLevelService = mock(UserLevelService.class);
        service = new GreetingServiceImpl();
        ReflectionTestUtils.setField(service, "messageService", messageService);
        ReflectionTestUtils.setField(service, "userLevelService", userLevelService);
    }

    private void givenBirthday(String birthday) {
        UserLevelDTO user = new UserLevelDTO();
        user.setBirthday(birthday);
        when(userLevelService.selectUserLevel()).thenReturn(user);
    }

    private void birthdayAlreadySent(boolean sent) {
        when(messageService.exists(eq(MessageType.GREETING), eq("生日快乐"), any(), any())).thenReturn(sent);
    }

    private void verifyBirthdayCardPushed() {
        verify(messageService).pushMessage(eq("生日快乐"), any(), isNull(),
                eq(MessageType.GREETING), isNull(), eq(GreetingCard.BIRTHDAY));
    }

    private void verifyBirthdayCardNotPushed() {
        verify(messageService, never()).pushMessage(eq("生日快乐"), any(), any(), any(), any(), any());
    }

    @Test
    void birthdayFullDate_pushesCard() {
        givenBirthday(LocalDate.now().toString());   // yyyy-MM-dd，月日必命中当天
        birthdayAlreadySent(false);

        service.dailyGreeting();

        verifyBirthdayCardPushed();
    }

    @Test
    void birthdayMonthDayOnly_pushesCard() {
        LocalDate now = LocalDate.now();
        givenBirthday(String.format("%02d-%02d", now.getMonthValue(), now.getDayOfMonth()));   // MM-dd
        birthdayAlreadySent(false);

        service.dailyGreeting();

        verifyBirthdayCardPushed();
    }

    @Test
    void birthdayAlreadySentToday_skips() {
        givenBirthday(LocalDate.now().toString());
        birthdayAlreadySent(true);

        service.dailyGreeting();

        verifyBirthdayCardNotPushed();
    }

    @Test
    void noBirthday_noCard() {
        givenBirthday(null);

        service.dailyGreeting();

        verifyBirthdayCardNotPushed();
    }
}
