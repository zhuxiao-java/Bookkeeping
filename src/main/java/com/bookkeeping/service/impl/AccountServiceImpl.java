package com.bookkeeping.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.bookkeeping.constant.BookkeepingResp;
import com.bookkeeping.dao.entity.AccountEntity;
import com.bookkeeping.dao.mapper.AccountMapper;
import com.bookkeeping.dao.dto.AccountDTO;
import com.bookkeeping.dao.dto.TransactionDTO;
import com.bookkeeping.dao.mapping.AccountMapping;
import com.bookkeeping.exception.BusinessException;
import com.bookkeeping.service.AccountService;
import org.sf.service.impl.IBaseCrudServiceImpl;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * 账户相关service
 * @author zhuxiao
 */
@Service
public class AccountServiceImpl extends IBaseCrudServiceImpl<AccountDTO, AccountEntity, AccountMapper, AccountMapping> implements AccountService {

    public AccountServiceImpl(AccountMapping mapping) {
        super(mapping);
    }

    @Override
    protected void savePreCheck(AccountEntity entity) {
        String name = entity.getName();
        QueryWrapper<AccountEntity> qw = new QueryWrapper<>();
        qw.eq("f_name", name);
        if (super.exists(qw)) {
            throw new BusinessException(BookkeepingResp.ACCOUNT_EXISTS);
        }
    }

    @Override
    public void applyEffect(TransactionDTO tx, boolean reverse) {
        if (tx == null || tx.getType() == null) {
            return;
        }
        BigDecimal sign = reverse ? BigDecimal.valueOf(-1) : BigDecimal.ONE;
        BigDecimal amount = nz(tx.getAmount());
        BigDecimal fee = nz(tx.getFee());
        switch (tx.getType()) {
            case INCOME -> adjust(tx.getAccountId(), amount.multiply(sign));
            case EXPENSE, REVERSAL -> adjust(tx.getAccountId(), amount.negate().multiply(sign));
            case TRANSFER -> {
                // 转出方扣 amount+fee（手续费不再凭空消失），转入方加 amount
                adjust(tx.getAccountId(), amount.add(fee).negate().multiply(sign));
                adjust(tx.getToAccountId(), amount.multiply(sign));
            }
        }
    }

    @Override
    public void setBalance(Integer accountId, BigDecimal balance) {
        if (accountId == null) {
            return;
        }
        UpdateWrapper<AccountEntity> uw = new UpdateWrapper<>();
        uw.eq("f_id", accountId);
        uw.set("f_current_balance", nz(balance));
        update(uw);
    }

    /**
     * 原子增减：编译为 balance = balance ± x，无读-改-写竞态；delta 为 0 时跳过。
     */
    private void adjust(Integer accountId, BigDecimal delta) {
        if (accountId == null || delta == null || delta.signum() == 0) {
            return;
        }
        UpdateWrapper<AccountEntity> uw = new UpdateWrapper<>();
        uw.eq("f_id", accountId);
        if (delta.signum() > 0) {
            uw.setIncrBy("f_current_balance", delta);
        } else {
            uw.setDecrBy("f_current_balance", delta.negate());
        }
        update(uw);
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
