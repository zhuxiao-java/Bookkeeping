package com.bookkeeping.service;

import com.bookkeeping.dao.dto.CategoryDTO;
import org.apache.commons.collections4.CollectionUtils;
import org.sf.service.IBaseCrudService;

import java.util.List;
import java.util.Objects;

/**
 * 收支service
 *
 * @author zx
 */
public interface CategoryService extends IBaseCrudService<CategoryDTO> {

    String getCategoryNameById(Integer id);

    List<CategoryTree> getCategoryListByIds(List<Integer> parentIdList);

    List<CategoryTree> getCategoryTreeListByIds(List<Integer> categoryId);

    record CategoryTree(Integer categoryId, String categoryName, String icon, String color,
                        List<CategoryTree> children) {

        public CategoryTree(Integer categoryId, String categoryName, String icon, String color) {
            this(categoryId, categoryName, icon, color, null);
        }

        public CategoryTree chooseCategory(Integer categoryId) {
            if (Objects.equals(categoryId(), categoryId)) {
                return this;
            }
            if (CollectionUtils.isEmpty(children())) {
                return null;
            }
            for (CategoryTree child : children()) {
                CategoryTree tree = child.chooseCategory(categoryId);
                if (Objects.nonNull(tree)) {
                    return tree;
                }
            }
            return null;
        }

        public boolean isChild(Integer categoryId) {
            if (CollectionUtils.isEmpty(children)) {
                return false;
            }
            for (CategoryTree childCategory : children) {
                if (Objects.equals(childCategory.categoryId(), categoryId)
                        || childCategory.isChild(categoryId)) {
                    return true;
                }
            }
            return false;
        }
    }
}
