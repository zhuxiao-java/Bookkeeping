package com.bookkeeping.dao.mapping;

import com.bookkeeping.dao.dto.TagDTO;
import com.bookkeeping.dao.entity.TagEntity;
import org.mapstruct.Mapper;
import org.sf.service.mapping.IBaseMapping;

/**
 * 标签mapping
 * @author zx
 */
@Mapper(componentModel = "spring")
public interface TagMapping extends IBaseMapping<TagEntity, TagDTO> {
}
