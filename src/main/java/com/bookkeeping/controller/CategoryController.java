package com.bookkeeping.controller;

import com.bookkeeping.dao.dto.CategoryDTO;
import com.bookkeeping.service.CategoryService;
import org.sf.web.controller.IBaseController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 收支分类controller
 * @author zhuxiao
 */
@RestController
@RequestMapping("category")
public class CategoryController extends IBaseController<CategoryDTO, CategoryService> {

    public CategoryController(CategoryService service) {
        super(service);
    }
}
