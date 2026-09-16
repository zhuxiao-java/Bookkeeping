package com.bookkeeping.dao.mapping;

import com.bookkeeping.dao.dto.TransactionDTO;
import com.bookkeeping.dao.entity.TransactionEntity;
import org.mapstruct.Mapper;
import org.sf.service.mapping.IBaseMapping;

/**
 * 交易映射
 * @author zx
 */
@Mapper(componentModel = "spring")
public interface TransactionMapping extends IBaseMapping<TransactionEntity, TransactionDTO> {
}
