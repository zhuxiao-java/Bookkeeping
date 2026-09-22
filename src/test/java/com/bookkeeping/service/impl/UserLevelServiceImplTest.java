package com.bookkeeping.service.impl;

import java.util.List;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.bookkeeping.constant.ExpTransactionType;
import com.bookkeeping.dao.dto.LevelConfigDTO;
import com.bookkeeping.dao.dto.UserLevelDTO;
import com.bookkeeping.dao.mapping.UserLevelMapping;
import com.bookkeeping.dao.mapper.UserLevelMapper;
import com.bookkeeping.service.ExpTransactionService;
import com.bookkeeping.service.LevelConfigService;
import com.bookkeeping.service.MessageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
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
    private MessageService messageService;
    private UserLevelServiceImpl service;

    @BeforeEach
    void setUp() {
        configService = mock(LevelConfigService.class);
        expTransactionService = mock(ExpTransactionService.class);
        messageService = mock(MessageService.class);
        service = spy(new UserLevelServiceImpl(mock(UserLevelMapping.class)));
        ReflectionTestUtils.setField(service, "configService", configService);
        ReflectionTestUtils.setField(service, "expTransactionService", expTransactionService);
        ReflectionTestUtils.setField(service, "messageService", messageService);
        UserLevelMapper mapper = mock(UserLevelMapper.class);
        when(mapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);

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
    private List<UpdateWrapper> captureWrappers() {
        ArgumentCaptor<UpdateWrapper> captor = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(service, atLeastOnce()).update(captor.capture());
        return captor.getAllValues();
    }

    private String captureSqlSet(int index) {
        // 归一化空白，兼容不同 MyBatis-Plus 版本的 "col=col - val" / "col=col-val" 格式
        return captureWrappers().get(index).getSqlSet().replaceAll("\\s+", "");
    }

    @Test
    void deduct_decrementsExperience_notAddTotalEarned() {
        service.gainExperience(ExpTransactionType.BUDGET, 0, "月末结算", -200);
        // 经验增减在第一次 update（等级在经验落库后的第二次 update 单独写入）
        String sqlSet = captureSqlSet(0);
        // 超支：f_experience 应减 200（修复前 setDecrBy(-200) => 实际 +200）
        assertTrue(sqlSet.contains("f_experience=f_experience-200"), sqlSet);
        // 扣减不应累加 total_earned
        assertFalse(sqlSet.contains("f_total_earned"), sqlSet);
        assertTrue(sqlSet.contains("f_total_spent=f_total_spent+200"), sqlSet);
    }

    @Test
    void increase_incrementsExperience_andTotalEarned() {
        service.gainExperience(ExpTransactionType.BUDGET, 0, "月末结算", 500);
        String sqlSet = captureSqlSet(0);
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
        doReturn(levelDto(20, 19000)).when(service).detail(any());
        assertEquals(0, service.gainRecordExperience());
        verify(service, never()).gainExperience(any(), anyInt(), any(), anyInt());
    }

    @Test
    void recordExperience_nearMax_returnsClippedReward() {
        doReturn(levelDto(19, 18998)).when(service).detail(any());
        assertEquals(2, service.gainRecordExperience());
    }

    @Test
    void failedExperienceUpdate_doesNotReportReward() {
        doReturn(false).when(service).update(any(Wrapper.class));
        assertThrows(IllegalStateException.class, () -> service.gainRecordExperience());
        verify(expTransactionService, never()).recordExpTransaction(anyInt(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void extremeReward_doesNotOverflowIntoDeduction() {
        assertEquals(18000, service.gainExperience(ExpTransactionType.OTHER, 0, "奖励", Integer.MAX_VALUE));
        assertTrue(captureWrappers().get(0).getParamNameValuePairs().containsValue(19000));
    }

    private UserLevelDTO levelDto(int level, Integer experience) {
        UserLevelDTO dto = new UserLevelDTO();
        dto.setLevel(level);
        dto.setExperience(experience);
        return dto;
    }

    /** 等级判定必须基于落库后的最新经验，而不是进入函数时的旧快照 */
    @Test
    void level_usesReloadedExperience_notStaleSnapshot() {
        // 快照：Lv.2 / 1000；扣 200 后库中实际 800，低于 Lv.2 阈值 -> 应降级为 Lv.1
        doReturn(levelDto(2, 1000)).doReturn(levelDto(2, 800)).when(service).detail(any());
        LevelConfigDTO demoted = new LevelConfigDTO();
        demoted.setLevel(1);
        demoted.setName("新手");
        when(configService.promotionLevel(anyInt())).thenReturn(demoted);

        service.gainExperience(ExpTransactionType.BUDGET, 0, "月末结算", -200);

        String levelSqlSet = captureSqlSet(1);
        // set("f_level", v) 走占位符，实际值在 paramNameValuePairs 中
        assertTrue(levelSqlSet.startsWith("f_level="), levelSqlSet);
        assertTrue(captureWrappers().get(1).getParamNameValuePairs().containsValue(1));
        verify(messageService).pushMessage(any(), any(), any(), any(), any());
    }

    /** 等级以传入经验（旧快照）判定会误升级：旧快照 1000 + 500 看似达阈值，但库中真实经验仅 1200 仍属 Lv.1 */
    @Test
    void level_notPromotedWhenReloadedExperienceBelowThreshold() {
        doReturn(levelDto(1, 1000)).doReturn(levelDto(1, 1200)).when(service).detail(any());
        LevelConfigDTO keep = new LevelConfigDTO();
        keep.setLevel(1);
        keep.setName("新手");
        when(configService.promotionLevel(anyInt())).thenReturn(keep);
        ArgumentCaptor<Integer> expCaptor = ArgumentCaptor.forClass(Integer.class);

        service.gainExperience(ExpTransactionType.RECORD, 0, "记一笔", 200);

        // 判定等级用的是重读到的 1200，而非 1000+200 的预估值
        verify(configService).promotionLevel(expCaptor.capture());
        assertEquals(1200, expCaptor.getValue());
        verify(service, times(1)).update(any(Wrapper.class));
        verify(messageService, never()).pushMessage(any(), any(), any(), any(), any());
    }

    /** 扣减不得把经验压成负数：不足 200 时只扣到 0 */
    @Test
    void deduct_floorsAtZero() {
        doReturn(levelDto(1, 100)).when(service).detail(any());

        service.gainExperience(ExpTransactionType.BUDGET, 0, "月末结算", -200);

        assertTrue(captureSqlSet(0).contains("f_experience=f_experience-100"));
        assertTrue(captureSqlSet(0).contains("f_total_spent=f_total_spent+100"));
        verify(expTransactionService).recordExpTransaction(0, "月末结算", ExpTransactionType.BUDGET, 100, -100);
    }

    /** 接近满级时经验直接写死为封顶值，等级按落库后经验判定（仍为满级则不写 f_level） */
    @Test
    void nearMaxLevel_writesCappedExperience_andKeepsMaxLevel() {
        // 快照 18950 + 100 超过 19000；重读为封顶后的 19000
        doReturn(levelDto(20, 18950)).doReturn(levelDto(20, 19000)).when(service).detail(any());
        LevelConfigDTO max = new LevelConfigDTO();
        max.setLevel(20);
        max.setName("大师");
        when(configService.promotionLevel(anyInt())).thenReturn(max);

        service.gainExperience(ExpTransactionType.BUDGET, 0, "月末结算", 100);

        assertTrue(captureSqlSet(0).contains("f_experience="), captureSqlSet(0));
        // 写入的是封顶后的 19000，而不是 19050
        assertTrue(captureWrappers().get(0).getParamNameValuePairs().containsValue(19000),
                String.valueOf(captureWrappers().get(0).getParamNameValuePairs()));
        assertTrue(captureSqlSet(0).contains("f_total_earned=f_total_earned+50"));
        verify(expTransactionService).recordExpTransaction(0, "月末结算", ExpTransactionType.BUDGET, 18950, 50);
        // 等级未变 -> 不应再发第二条 update 写 f_level，也不推等级消息
        verify(service, times(1)).update(any(Wrapper.class));
        verify(messageService, never()).pushMessage(any(), any(), any(), any(), any());
    }
}
