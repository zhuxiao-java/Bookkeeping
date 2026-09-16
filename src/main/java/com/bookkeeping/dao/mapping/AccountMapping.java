package com.bookkeeping.dao.mapping;

import com.bookkeeping.dao.dto.AccountDTO;
import com.bookkeeping.dao.entity.AccountEntity;
import org.mapstruct.Mapper;
import org.sf.service.mapping.IBaseMapping;

/**
 *
 * @author
 */
@Mapper(componentModel = "spring")
public interface AccountMapping extends IBaseMapping<AccountEntity, AccountDTO> {
}
