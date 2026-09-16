package com.bookkeeping.dao.mapping;

import com.bookkeeping.dao.dto.LevelConfigDTO;
import com.bookkeeping.dao.entity.LevelConfigEntity;
import org.mapstruct.Mapper;
import org.sf.service.mapping.IBaseMapping;

/**
 * 等级配置映射
 * @author zx
 */
@Mapper(componentModel = "spring")
public interface LevelConfigMapping extends IBaseMapping<LevelConfigEntity, LevelConfigDTO> {
}
