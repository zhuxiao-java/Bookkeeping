package com.bookkeeping.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bookkeeping.constant.BookkeepingResp;
import com.bookkeeping.dao.dto.CategoryDTO;
import com.bookkeeping.dao.entity.CategoryEntity;
import com.bookkeeping.dao.mapper.CategoryMapper;
import com.bookkeeping.dao.mapping.CategoryMapping;
import com.bookkeeping.exception.BusinessException;
import com.bookkeeping.service.CategoryService;
import org.sf.service.impl.IBaseCrudServiceImpl;
import org.sf.util.StreamUtil;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 收支分类Service实现类
 *
 * @author zhuxiao
 */
@Service
public class CategoryServiceImpl extends IBaseCrudServiceImpl<CategoryDTO, CategoryEntity, CategoryMapper, CategoryMapping> implements CategoryService {

    public CategoryServiceImpl(CategoryMapping mapping) {
        super(mapping);
    }

    @Override
    protected void savePreCheck(CategoryEntity entity) {
        QueryWrapper<CategoryEntity> qw = new QueryWrapper<>();
        qw.eq("f_name", entity.getName());
        if (super.exists(qw)) {
            throw new BusinessException(BookkeepingResp.CATEGORY_EXISTS);
        }
    }

    @Override
    public String getCategoryNameById(Integer id) {
        if (Objects.isNull(id)) {
            return null;
        }
        CategoryDTO detail = detail(id);
        return Objects.isNull(detail) ? null : detail.getName();
    }

    @Override
    public List<CategoryTree> getCategoryListByIds(List<Integer> parentIdList) {
        List<CategoryTree> treeList = new ArrayList<>();
        for (Integer parentId : parentIdList) {
            CategoryDTO dto = detail(parentId);
            List<CategoryEntity> list = baseMapper.getCategoryListByIds(parentId);
            List<CategoryTree> childList = StreamUtil.map(list, entity -> new CategoryTree(entity.getId(), entity.getName(), entity.getColor(), entity.getIcon()));
            treeList.add(
                    new CategoryTree(parentId, dto.getName(), dto.getIcon(), dto.getColor(), childList)
            );
        }
        return treeList;
    }

    @Override
    public List<CategoryTree> getCategoryTreeListByIds(List<Integer> idList) {
        HashSet<Integer> idSet = new HashSet<>(idList);
        List<CategoryTree> treeList = new ArrayList<>();
        for (Integer id : idSet) {
            List<CategoryEntity> entityList = baseMapper.getAllCategoryByCategoryId(id);
            CategoryEntity first = entityList.remove(0);
            List<CategoryTree> childList = StreamUtil.map(entityList, entity -> new CategoryTree(entity.getId(), entity.getName(), entity.getColor(), entity.getIcon()));
            treeList.add(
                    new CategoryTree(first.getId(), first.getName(), first.getIcon(), first.getColor(), childList)
            );

        }
        return treeList;
    }
}
