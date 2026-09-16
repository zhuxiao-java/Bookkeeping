package com.bookkeeping.dao.mapping;

import com.bookkeeping.dao.dto.ExpTransactionDTO;
import com.bookkeeping.dao.entity.ExpTransactionEntity;
import org.mapstruct.Mapper;
import org.sf.service.mapping.IBaseMapping;

/**
 * 经验变更流水mapping
 * @author
 */
@Mapper(componentModel = "spring")
public interface ExpTransactionMapping extends IBaseMapping<ExpTransactionEntity, ExpTransactionDTO> {
}
