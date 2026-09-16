package com.bookkeeping.dao.mapper;

import com.bookkeeping.dao.entity.CategoryEntity;
import org.apache.ibatis.annotations.Param;
import org.sf.dao.mapper.IBaseMapper;

import java.util.List;

/**
 * 收支分类mapper
 * @author zx
 */
public interface CategoryMapper extends IBaseMapper<CategoryEntity> {

    List<CategoryEntity> getCategoryListByIds(@Param("id") Integer id);

    /**
     * 根据id获取所有分类,包括父、子
     * @param id categoryId
     * @return CategoryEntity
     */
    List<CategoryEntity> getAllCategoryByCategoryId(@Param("id")Integer id);
}
