package com.bookkeeping.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.bookkeeping.constant.ExpTransactionType;
import com.bookkeeping.constant.GreetingCard;
import com.bookkeeping.constant.MessageBizType;
import com.bookkeeping.constant.MessageType;
import com.bookkeeping.dao.dto.BudgetDTO;
import com.bookkeeping.dao.dto.LevelConfigDTO;
import com.bookkeeping.dao.dto.UserLevelDTO;
import com.bookkeeping.dao.entity.UserLevelEntity;
import com.bookkeeping.dao.mapper.UserLevelMapper;
import com.bookkeeping.dao.mapping.UserLevelMapping;
import com.bookkeeping.service.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import org.apache.commons.collections4.CollectionUtils;
import org.sf.service.impl.IBaseCrudServiceImpl;
import org.sf.util.StreamUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

/**
 * 用户等级Service实现类
 *
 * @author zhuxiao
 */
@Service
public class UserLevelServiceImpl extends IBaseCrudServiceImpl<UserLevelDTO, UserLevelEntity, UserLevelMapper, UserLevelMapping> implements UserLevelService {
    @Resource
    private ExpTransactionService expTransactionService;
    @Resource
    private BudgetService budgetService;
    @Resource
    private TransactionService transactionService;
    @Resource
    private ExperienceLogService experienceLogService;
    @Resource
    private LevelConfigService configService;
    @Resource
    private MessageService messageService;
    /**
     * 超支时扣除200经验值
     */
    private static final int DEDUCT = -200;
    /**
     * 节省时增加500经验值
     */
    private static final int INCREASE = 500;
    /**
     * 单机部署情况下只有一个用户所以主键为1
     */
    private static final int PRIMARY_ID = 1;
    /**
     * 里程碑贺卡步长：升到 5 的整数倍等级时额外推送庆祝贺卡
     */
    private static final int MILESTONE_STEP = 5;
    /**
     * 记账奖励：今日首笔经验
     */
    private static final int RECORD_FIRST_EXP = 8;
    /**
     * 记账奖励：当日后续每笔经验
     */
    private static final int RECORD_PER_EXP = 1;
    /**
     * 记账奖励：单日发放经验封顶（防拆单/反复记删刷经验）
     */
    private static final int RECORD_DAILY_CAP = 15;
    /**
     * 记账奖励经验流水描述
     */
    private static final String RECORD_DESCRIPTION = "记一笔";

    public UserLevelServiceImpl(UserLevelMapping mapping) {
        super(mapping);
    }

    @Override
    public UserLevelDTO selectUserLevel() {
        return super.selectAll().get(0);
    }

    @Override
    public void gainExperience(ExpTransactionType type, int refId, String description, int experience) {
        UserLevelDTO detail = detail(PRIMARY_ID);

        UpdateWrapper<UserLevelEntity> uw = new UpdateWrapper<>();

        uw.eq("f_id", PRIMARY_ID);


        int newExperience = detail.getExperience() + experience;

        LevelConfigDTO configDTO = configService.promotionLevel(newExperience);

        if (!Objects.equals(configDTO.getLevel(), detail.getLevel())) {
            uw.set("f_level", configDTO.getLevel());
        }

        if (newExperience > LevelConfigService.MAX_LEVEL_EXP_THRESHOLD) {
            uw.set("f_experience", LevelConfigService.MAX_LEVEL_EXP_THRESHOLD);
        } else {
            if (experience >= 0) {
                uw.setIncrBy("f_experience", experience);
            } else {
                // experience 为负，setDecrBy 需传正数（否则减去负数 = 加，符号反转）
                uw.setDecrBy("f_experience", -experience);
            }
        }

        if (experience > 0) {
            uw.setIncrBy("f_total_earned", experience);
        }

        boolean updated = update(uw);
        if (updated) {
            expTransactionService.recordExpTransaction(refId, description, type, detail.getExperience(), experience);
            if (!Objects.equals(configDTO.getLevel(), detail.getLevel())) {
                notifyLevelChange(detail.getLevel(), configDTO.getLevel(), configDTO.getName());
            }
        }
    }

    @Override
    public int gainRecordExperience() {
        if (hitMaxLevel()) {
            return 0;
        }
        int awardedToday = expTransactionService.sumTodayExpBySource(ExpTransactionType.RECORD);
        if (awardedToday >= RECORD_DAILY_CAP) {
            return 0;
        }
        // 当日首笔给较大奖励（仪式感），后续每笔小额，并统一受单日封顶约束
        int exp = awardedToday == 0 ? RECORD_FIRST_EXP : RECORD_PER_EXP;
        exp = Math.min(exp, RECORD_DAILY_CAP - awardedToday);
        if (exp <= 0) {
            return 0;
        }
        // refId 传 0：insert 后 dto.id 未回填，与月末结算(BUDGET)一致；封顶靠按 source+当日求和，不依赖 refId
        gainExperience(ExpTransactionType.RECORD, 0, RECORD_DESCRIPTION, exp);
        return exp;
    }

    /**
     * 等级变动站内信：升/降级各一条 LEVEL 消息；升到 MILESTONE_STEP 整数倍时额外推送庆祝贺卡。
     */
    private void notifyLevelChange(int oldLevel, int newLevel, String newLevelName) {
        boolean up = newLevel > oldLevel;
        String title = up ? "等级提升" : "等级下降";
        String content = up
                ? String.format("恭喜！你的等级从 Lv.%d 提升到 Lv.%d「%s」，继续保持好习惯～", oldLevel, newLevel, newLevelName)
                : String.format("很遗憾，你的等级从 Lv.%d 下降到 Lv.%d「%s」。别灰心，下个月一起把开销压回来～", oldLevel, newLevel, newLevelName);
        messageService.pushMessage(title, content, null, MessageType.LEVEL, MessageBizType.LEVEL);
        if (up && newLevel % MILESTONE_STEP == 0) {
            String card = String.format("🎉 里程碑达成！\n你已晋升到 Lv.%d「%s」。\n记账之路越走越稳，为你喝彩！", newLevel, newLevelName);
            messageService.pushMessage("等级里程碑", card, null, MessageType.GREETING, MessageBizType.LEVEL, GreetingCard.MILESTONE);
        }
    }

    @Override
    public void updateBirthday(String birthday) {
        UpdateWrapper<UserLevelEntity> uw = new UpdateWrapper<>();
        uw.eq("f_id", PRIMARY_ID);
        uw.set("f_birthday", birthday);
        update(uw);
    }

    @Override
    public boolean hitMaxLevel() {
        LevelConfigDTO maxLevel = configService.selectMaxLevel();
        UserLevelDTO dto = detail(PRIMARY_ID);
        return Objects.equals(dto.getLevel(), maxLevel.getLevel());
    }

    @Override
    public void monthlyLevelChange() {
        LocalDate now = LocalDate.now(ZoneId.systemDefault());
        LocalDate before = now.plusMonths(-1);
        if (experienceLogService.exists(before.getYear(), before.getMonthValue())) {
            return;
        }
        List<BudgetDTO> dtoList = budgetService.selectBudgetByYearMonth(before.getYear(), before.getMonthValue());
        if (CollectionUtils.isEmpty(dtoList)) {
            return;
        }
        BigDecimal budgetSum = StreamUtil.sum(dtoList, BudgetDTO::getAmount);
        BigDecimal expenseSum = transactionService.selectBeforeExpenseAmount(before);
        BigDecimal diff = budgetSum.subtract(expenseSum);
        int levelDiff = 0;
        if (diff.compareTo(BigDecimal.ZERO) < 0) {
            levelDiff = DEDUCT;
        } else if (diff.compareTo(BigDecimal.ZERO) > 0) {
            levelDiff = INCREASE;
        }
        gainExperience(ExpTransactionType.BUDGET, 0, "月末结算", levelDiff);
        experienceLogService.recordExperienceLog(before.getYear(), before.getMonthValue(), budgetSum, expenseSum, levelDiff);
        // 月末结算：整月超支推送汇总 BUDGET 站内信；本方法开头已按 experienceLog 去重，天然每月一次
        if (diff.compareTo(BigDecimal.ZERO) < 0) {
            String content = String.format("%d 年 %d 月支出 %s，超出预算 %s（预算 %s），已扣除 %d 经验。下个月一起把开销压回来～",
                    before.getYear(), before.getMonthValue(), expenseSum, diff.abs(), budgetSum, -DEDUCT);
            messageService.pushMessage("月末预算超支", content, null, MessageType.BUDGET, MessageBizType.BUDGET);
        }
    }

    @PostConstruct
    @Transactional(rollbackFor = Exception.class)
    public void init() {
        monthlyLevelChange();
    }
}
