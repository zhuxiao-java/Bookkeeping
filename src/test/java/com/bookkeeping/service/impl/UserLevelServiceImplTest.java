package com.bookkeeping.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.bookkeeping.constant.ExpTransactionType;
import com.bookkeeping.dao.dto.LevelConfigDTO;
import com.bookkeeping.dao.dto.UserLevelDTO;
import com.bookkeeping.dao.mapping.UserLevelMapping;
import com.bookkeeping.service.ExpTransactionService;
import com.bookkeeping.service.LevelConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 覆盖 P0-2：gainExperience 的经验增减符号。
 * 超支(experience<0) 必须扣经验，节省(experience>0) 必须加经验且累加 total_earned。
 * 通过捕获 UpdateWrapper.getSqlSet() 断言实际生成的增减方向，避开真实 SQL 执行。
 */
class UserLevelServiceImplTest {

    private LevelConfigService configService;
    private ExpTransactionService expTransactionService;
    private UserLevelServiceImpl service;

    @BeforeEach
    void setUp() {
        configService = mock(LevelConfigService.class);
        expTransactionService = mock(ExpTransactionService.class);
        service = spy(new UserLevelServiceImpl(mock(UserLevelMapping.class)));
        ReflectionTestUtils.setField(service, "configService", configService);
        ReflectionTestUtils.setField(service, "expTransactionService", expTransactionService);

        UserLevelDTO detail = new UserLevelDTO();
        detail.setLevel(1);
        detail.setExperience(1000);   // 基准经验，确保 newExperience 远低于 MAX(19000)
        doReturn(detail).when(service).detail(any());

        LevelConfigDTO cfg = new LevelConfigDTO();
        cfg.setLevel(1);              // 与当前等级一致 -> 不写 f_level，隔离经验符号断言
        when(configService.promotionLevel(anyInt())).thenReturn(cfg);

        // 默认未满级（最高等级 20 > 当前 1），使 hitMaxLevel() 返回 false
        LevelConfigDTO maxCfg = new LevelConfigDTO();
        maxCfg.setLevel(20);
        when(configService.selectMaxLevel()).thenReturn(maxCfg);

        doReturn(true).when(service).update(any(Wrapper.class));
    }

    @SuppressWarnings("rawtypes")
    private String captureSqlSet() {
        ArgumentCaptor<UpdateWrapper> captor = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(service).update(captor.capture());
        // 归一化空白，兼容不同 MyBatis-Plus 版本的 "col=col - val" / "col=col-val" 格式
        return captor.getValue().getSqlSet().replaceAll("\\s+", "");
    }

    @Test
    void deduct_decrementsExperience_notAddTotalEarned() {
        service.gainExperience(ExpTransactionType.BUDGET, 0, "月末结算", -200);
        String sqlSet = captureSqlSet();
        // 超支：f_experience 应减 200（修复前 setDecrBy(-200) => 实际 +200）
        assertTrue(sqlSet.contains("f_experience=f_experience-200"), sqlSet);
        // 扣减不应累加 total_earned
        assertFalse(sqlSet.contains("f_total_earned"), sqlSet);
    }

    @Test
    void increase_incrementsExperience_andTotalEarned() {
        service.gainExperience(ExpTransactionType.BUDGET, 0, "月末结算", 500);
        String sqlSet = captureSqlSet();
        assertTrue(sqlSet.contains("f_experience=f_experience+500"), sqlSet);
        assertTrue(sqlSet.contains("f_total_earned=f_total_earned+500"), sqlSet);
    }

    /** 记账奖励：当日尚无发放（首笔）-> +8 */
    @Test
    void recordExperience_firstToday_awards8() {
        when(expTransactionService.sumTodayExpBySource(ExpTransactionType.RECORD)).thenReturn(0);
        assertEquals(8, service.gainRecordExperience());
    }

    /** 记账奖励：当日已发首笔（后续）-> +1 */
    @Test
    void recordExperience_subsequent_awards1() {
        when(expTransactionService.sumTodayExpBySource(ExpTransactionType.RECORD)).thenReturn(8);
        assertEquals(1, service.gainRecordExperience());
    }

    /** 记账奖励：已达单日封顶 15 -> 不发，不调 gainExperience */
    @Test
    void recordExperience_dailyCapReached_awards0() {
        when(expTransactionService.sumTodayExpBySource(ExpTransactionType.RECORD)).thenReturn(15);
        assertEquals(0, service.gainRecordExperience());
        verify(service, never()).gainExperience(any(), anyInt(), any(), anyInt());
    }

    /** 记账奖励：满级 -> 不发，不调 gainExperience */
    @Test
    void recordExperience_maxLevel_awards0() {
        LevelConfigDTO max = new LevelConfigDTO();
        max.setLevel(1);              // 与当前等级一致 -> hitMaxLevel() 为 true
        when(configService.selectMaxLevel()).thenReturn(max);
        assertEquals(0, service.gainRecordExperience());
        verify(service, never()).gainExperience(any(), anyInt(), any(), anyInt());
    }
}
