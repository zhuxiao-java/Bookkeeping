package com.bookkeeping.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.bookkeeping.constant.BookkeepingResp;
import com.bookkeeping.constant.ExpTransactionType;
import com.bookkeeping.constant.MessageBizType;
import com.bookkeeping.constant.MessageType;
import com.bookkeeping.dao.dto.CheckInDTO;
import com.bookkeeping.dao.entity.CheckInEntity;
import com.bookkeeping.dao.mapper.CheckInMapper;
import com.bookkeeping.dao.mapping.CheckInMapping;
import com.bookkeeping.exception.BusinessException;
import com.bookkeeping.service.CheckInService;
import com.bookkeeping.service.MessageService;
import com.bookkeeping.service.UserLevelService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.sf.service.impl.IBaseCrudServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

/**
 * 签到service实现
 *
 * @author zhuxiao
 */
@Service
@Slf4j
public class CheckInServiceImpl extends IBaseCrudServiceImpl<CheckInDTO, CheckInEntity, CheckInMapper, CheckInMapping> implements CheckInService {
    /**
     * 每日签到基础经验
     */
    private static final int BASE_EXP = 10;
    /**
     * 每连续签到一天额外增加的经验值
     */
    private static final int BONUS_PER_DAY = 2;
    /**
     * 额外奖励经验上限
     */
    private static final int MAX_BONUS_EXP = 50;

    private static final String DESCRIPTION = "每日签到";
    @Resource
    private UserLevelService levelService;
    @Resource
    private MessageService messageService;

    public CheckInServiceImpl(CheckInMapping mapping) {
        super(mapping);
    }

    @Override
    protected void savePreCheck(CheckInEntity entity) {
        QueryWrapper<CheckInEntity> qw = new QueryWrapper<>();
        qw.eq("f_check_date", entity.getCheckDate());
        if (super.exists(qw)) {
            throw new BusinessException(BookkeepingResp.CHECK_IN_EXISTS);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void dailyCheckIn() {
        LocalDate checkDate = LocalDate.now(ZoneId.systemDefault());
        QueryWrapper<CheckInEntity> qw = new QueryWrapper<>();
        qw.eq("f_check_date", checkDate);
        if (super.exists(qw)) {
            log.info("今日签到任务已完成");
        } else {
            QueryWrapper<CheckInEntity> beforeQw = new QueryWrapper<>();
            beforeQw.eq("f_check_date", checkDate.plusDays(-1));
            Optional<CheckInEntity> beforeCheckInOpt = super.getOneOpt(beforeQw);
            CheckInDTO checkInDTO = new CheckInDTO();
            checkInDTO.setCheckDate(checkDate);
            checkInDTO.setStreakDays(beforeCheckInOpt.map(CheckInEntity::getStreakDays).orElse(0) + 1);
            checkInDTO.setBaseExp(BASE_EXP);
            int bonusExp = Math.min((checkInDTO.getStreakDays() - 1) * BONUS_PER_DAY, MAX_BONUS_EXP);
            checkInDTO.setBonusExp(bonusExp);
            checkInDTO.setExpReward(checkInDTO.getBaseExp() + bonusExp);
            if (super.insert(checkInDTO)) {
                beforeQw = new QueryWrapper<>();
                beforeQw.eq("f_check_date", checkDate);
                CheckInEntity inEntity = super.getOne(beforeQw);
                int actualReward = levelService.gainExperience(ExpTransactionType.CHECK_IN, inEntity.getId(), DESCRIPTION, checkInDTO.getExpReward());
                if (actualReward != checkInDTO.getExpReward()) {
                    int actualBase = Math.min(BASE_EXP, actualReward);
                    UpdateWrapper<CheckInEntity> reward = new UpdateWrapper<>();
                    reward.eq("f_id", inEntity.getId()).set("f_exp_reward", actualReward)
                            .set("f_base_exp", actualBase).set("f_bonus_exp", actualReward - actualBase);
                    if (!update(reward)) {
                        throw new IllegalStateException("签到经验更新失败");
                    }
                }
                log.info("今日签到成功，获得{}经验", actualReward);
                String content = beforeCheckInOpt.isPresent() ? "您已连续签到" + checkInDTO.getStreakDays() + "天" : "您已签到";
                String expMsg = actualReward == 0 ? "，已达等级上限，本次未增加经验" : "，获得" + actualReward + "经验，再接再厉呀";
                messageService.pushMessage("每日签到", content + expMsg, inEntity.getId(), MessageType.CHECK_IN, MessageBizType.LEVEL);
            }
        }
    }

}
