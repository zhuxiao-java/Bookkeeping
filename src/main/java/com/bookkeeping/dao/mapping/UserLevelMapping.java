package com.bookkeeping.dao.mapping;

import com.bookkeeping.dao.dto.UserLevelDTO;
import com.bookkeeping.dao.entity.UserLevelEntity;
import org.mapstruct.Mapper;
import org.sf.service.mapping.IBaseMapping;

/**
 * 用户等级映射
 * @author zx
 */
@Mapper(componentModel = "spring")
public interface UserLevelMapping extends IBaseMapping<UserLevelEntity, UserLevelDTO> {
}
