package com.bookkeeping.dao.mapping;

import com.bookkeeping.dao.dto.ExperienceLogDTO;
import com.bookkeeping.dao.entity.ExperienceLogEntity;
import org.mapstruct.Mapper;
import org.sf.service.mapping.IBaseMapping;

/**
 * 等级变更日志映射
 * @author zx
 */
@Mapper(componentModel = "spring")
public interface ExperienceLogMapping extends IBaseMapping<ExperienceLogEntity, ExperienceLogDTO> {
}
