package com.bookkeeping.dao.mapping;

import com.bookkeeping.dao.dto.BudgetDTO;
import com.bookkeeping.dao.entity.BudgetEntity;
import org.mapstruct.Mapper;
import org.sf.service.mapping.IBaseMapping;

/**
 * 预算映射
 * @author zx
 */
@Mapper(componentModel = "spring")
public interface BudgetMapping extends IBaseMapping<BudgetEntity, BudgetDTO> {
}
