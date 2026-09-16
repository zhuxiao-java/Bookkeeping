package com.bookkeeping.dao.mapping;

import com.bookkeeping.dao.dto.CategoryDTO;
import com.bookkeeping.dao.entity.CategoryEntity;
import org.mapstruct.Mapper;
import org.sf.service.mapping.IBaseMapping;

/**
 * 收支分类mapping
 * @author zx
 */
@Mapper(componentModel = "spring")
public interface CategoryMapping extends IBaseMapping<CategoryEntity, CategoryDTO> {
}
