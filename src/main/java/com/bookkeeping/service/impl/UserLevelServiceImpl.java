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
import jakarta.annotation.Resource;
import org.sf.service.impl.IBaseCrudServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
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
        UserLevelDTO dto = detail(PRIMARY_ID);
        // 存量记录可能被旧的文本门槛比较误判；展示以真实经验为准。
        dto.setLevel(Objects.requireNonNull(configService.promotionLevel(dto.getExperience()), "缺少等级配置").getLevel());
        return dto;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int gainExperience(ExpTransactionType type, int refId, String description, int experience) {
        lockExperienceAccount();
        UserLevelDTO detail = detail(PRIMARY_ID);
        Integer oldLevel = detail.getLevel();
        int currentExperience = Objects.isNull(detail.getExperience()) ? 0 : detail.getExperience();

        long requestedExperience = (long) currentExperience + experience;
        int newExperience = (int) Math.max(LevelConfigService.MIN_LEVEL_EXP_THRESHOLD,
                Math.min(requestedExperience, LevelConfigService.MAX_LEVEL_EXP_THRESHOLD));
        // 经验向下封顶：扣减不得越过 0，否则 promotionLevel 按 [0, exp] 区间查不到配置（返回 null），等级被错置甚至报错
        int changed = newExperience - currentExperience;
        // 满级封顶：经验与等级必须用同一个封顶值判定，避免经验写 19000 而等级按未封顶经验算出
        boolean capped = newExperience >= LevelConfigService.MAX_LEVEL_EXP_THRESHOLD;

        // 第一步：只落经验（原子增减，保留并发安全）与累计获得
        UpdateWrapper<UserLevelEntity> expWrapper = new UpdateWrapper<>();
        expWrapper.eq("f_id", PRIMARY_ID);
        if (capped) {
            expWrapper.set("f_experience", newExperience);
        } else if (changed >= 0) {
            expWrapper.setIncrBy("f_experience", changed);
        } else {
            // changed 为负，setDecrBy 需传正数（否则减去负数 = 加，符号反转）
            expWrapper.setDecrBy("f_experience", -changed);
        }
        if (changed > 0) {
            expWrapper.setIncrBy("f_total_earned", changed);
        } else if (changed < 0) {
            expWrapper.setIncrBy("f_total_spent", -changed);
        }
        if (!update(expWrapper)) {
            throw new IllegalStateException("经验更新失败");
        }

        // 第二步：以落库后的真实经验判定等级，杜绝用旧快照算出的等级写错值
        UserLevelDTO latest = detail(PRIMARY_ID);
        Integer finalExperience = latest.getExperience();
        int effectiveExperience = Objects.isNull(finalExperience) ? newExperience : finalExperience;
        LevelConfigDTO configDTO = configService.promotionLevel(
                Math.min(effectiveExperience, LevelConfigService.MAX_LEVEL_EXP_THRESHOLD));
        Integer newLevel = Objects.requireNonNull(configDTO, "缺少等级配置").getLevel();
        boolean levelChanged = !Objects.equals(newLevel, oldLevel);
        if (levelChanged) {
            UpdateWrapper<UserLevelEntity> levelWrapper = new UpdateWrapper<>();
            levelWrapper.eq("f_id", PRIMARY_ID);
            levelWrapper.set("f_level", newLevel);
            if (!update(levelWrapper)) {
                throw new IllegalStateException("等级更新失败");
            }
        }

        if (changed != 0) {
            expTransactionService.recordExpTransaction(refId, description, type, currentExperience, changed);
        }
        if (levelChanged && experience != 0) {
            notifyLevelChange(oldLevel, newLevel, configDTO.getName());
        }
        return changed;
    }

    /** SQLite 单写者锁须先于经验快照、每日限额与月结去重查询，并保持到事务提交。 */
    private void lockExperienceAccount() {
        UpdateWrapper<UserLevelEntity> lock = new UpdateWrapper<>();
        lock.eq("f_id", PRIMARY_ID).setSql("f_experience = f_experience");
        if (baseMapper.update(null, lock) != 1) {
            throw new IllegalStateException("用户等级记录不存在");
        }
    }

    @Override
    @Transactional(propagation = Propagation.NESTED, rollbackFor = Exception.class)
    public int gainRecordExperience() {
        // 保存点允许奖励失败单独回滚，不影响外层已经成功的记账与余额联动。
        lockExperienceAccount();
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
        return gainExperience(ExpTransactionType.RECORD, 0, RECORD_DESCRIPTION, exp);
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
        UserLevelDTO dto = detail(PRIMARY_ID);
        return Objects.requireNonNullElse(dto.getExperience(), 0) >= LevelConfigService.MAX_LEVEL_EXP_THRESHOLD;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void monthlyLevelChange() {
        lockExperienceAccount();
        LocalDate now = LocalDate.now(ZoneId.systemDefault());
        LocalDate before = now.withDayOfMonth(1).minusMonths(1);
        if (experienceLogService.exists(before.getYear(), before.getMonthValue())) {
            return;
        }
        BudgetDTO budget = budgetService.selectOneByCategoryId(before.getYear(), before.getMonthValue(), null);
        if (budget == null) {
            return;
        }
        // 分类预算是总预算的拆分，月结不能重复累加。
        BigDecimal budgetSum = budget.getAmount();
        BigDecimal expenseSum = transactionService.selectBeforeExpenseAmount(before);
        BigDecimal diff = budgetSum.subtract(expenseSum);
        int levelDiff = 0;
        if (diff.compareTo(BigDecimal.ZERO) < 0) {
            levelDiff = DEDUCT;
        } else if (diff.compareTo(BigDecimal.ZERO) > 0) {
            levelDiff = INCREASE;
        }
        int actualChange = gainExperience(ExpTransactionType.BUDGET, 0, "月末结算", levelDiff);
        experienceLogService.recordExperienceLog(before.getYear(), before.getMonthValue(), budgetSum, expenseSum, actualChange);
        // 月末结算：整月超支推送汇总 BUDGET 站内信；本方法开头已按 experienceLog 去重，天然每月一次
        if (diff.compareTo(BigDecimal.ZERO) < 0) {
            String content = String.format("%d 年 %d 月支出 %s，超出预算 %s（预算 %s），已扣除 %d 经验。下个月一起把开销压回来～",
                    before.getYear(), before.getMonthValue(), expenseSum, diff.abs(), budgetSum, -actualChange);
            messageService.pushMessage("月末预算超支", content, null, MessageType.BUDGET, MessageBizType.BUDGET);
        }
    }

}
