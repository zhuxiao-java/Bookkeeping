package com.bookkeeping.dao.mapping;

import com.bookkeeping.dao.dto.PetDTO;
import com.bookkeeping.dao.entity.PetEntity;
import org.mapstruct.Mapper;
import org.sf.service.mapping.IBaseMapping;

/**
 * 陪伴宠物映射。
 */
@Mapper(componentModel = "spring")
public interface PetMapping extends IBaseMapping<PetEntity, PetDTO> {
}
