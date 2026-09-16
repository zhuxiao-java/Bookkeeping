package com.bookkeeping.service;

import com.bookkeeping.constant.ExpTransactionType;
import com.bookkeeping.dao.dto.UserLevelDTO;
import org.sf.service.IBaseCrudService;

/**
 * 用户等级Service
 * @author
 */
public interface UserLevelService extends IBaseCrudService<UserLevelDTO> {

    UserLevelDTO selectUserLevel();

    void gainExperience(ExpTransactionType type, int refId, String description, int experience);

    /**
     * 记账经验奖励：今日首笔较大、后续每笔小额、单日封顶；满级不发。
     * 由 TransactionServiceImpl 在新增流水成功后调用，用于强化每日记账习惯。
     *
     * @return 本次实际发放的经验值（未发放时为 0）
     */
    int gainRecordExperience();

    /**
     * 是否达到最高等级
     * @return boolean true 达到 false 未达到
     */
    boolean hitMaxLevel();

    void monthlyLevelChange();

    /**
     * 设置单用户生日（yyyy-MM-dd 或 MM-dd），用于生日贺卡；传 null/空串则清除。
     */
    void updateBirthday(String birthday);
}
