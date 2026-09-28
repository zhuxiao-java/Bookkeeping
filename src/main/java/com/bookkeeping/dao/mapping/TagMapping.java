package com.bookkeeping.dao.mapping;

import com.bookkeeping.dao.dto.TagDTO;
import com.bookkeeping.dao.entity.TagEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.sf.service.mapping.IBaseMapping;

/**
 * 标签mapping
 * @author zx
 */
@Mapper(componentModel = "spring")
public interface TagMapping extends IBaseMapping<TagEntity, TagDTO> {
    @Mapping(source = "tagGroup", target = "group")
    TagDTO toDto(TagEntity var1);

    @Mapping(source = "group", target = "tagGroup")
    TagEntity toEntity(TagDTO tagDTO);
}
