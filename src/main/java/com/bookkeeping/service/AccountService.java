package com.bookkeeping.service;

import com.bookkeeping.dao.dto.AccountDTO;
import com.bookkeeping.dao.dto.TransactionDTO;
import org.sf.service.IBaseCrudService;

import java.math.BigDecimal;

/**
 * 账户Service
 * @author zx
 */
public interface AccountService extends IBaseCrudService<AccountDTO> {
    /**
     * 将一笔交易对账户余额的影响落地。
     * 收入：+amount；支出/冲正：-amount；转账：转出方 -(amount+fee)、转入方 +amount。
     *
     * @param tx      交易快照
     * @param reverse true 表示冲销（反向回滚该笔交易的影响）
     */
    void applyEffect(TransactionDTO tx, boolean reverse);

    /**
     * 直接设定账户当前余额（用于对账重算时复位到期初余额）。
     */
    void setBalance(Integer accountId, BigDecimal balance);
}
