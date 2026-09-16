package com.bookkeeping.dao.mapping;

import com.bookkeeping.dao.dto.CheckInDTO;
import com.bookkeeping.dao.entity.CheckInEntity;
import org.mapstruct.Mapper;
import org.sf.service.mapping.IBaseMapping;

/**
 * 签到映射
 * @author zx
 */
@Mapper(componentModel = "spring")
public interface CheckInMapping extends IBaseMapping<CheckInEntity, CheckInDTO> {
}
