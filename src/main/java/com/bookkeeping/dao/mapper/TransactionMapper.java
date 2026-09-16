package com.bookkeeping.dao.mapper;

import com.bookkeeping.dao.entity.TransactionEntity;
import org.sf.dao.mapper.IBaseMapper;

import java.util.List;

/**
 * 交易mapper
 * @author zx
 */
public interface TransactionMapper extends IBaseMapper<TransactionEntity> {
    List<TransactionEntity> recent();
}
