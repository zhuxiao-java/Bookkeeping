package com.bookkeeping.controller;

import com.bookkeeping.dao.dto.TagDTO;
import com.bookkeeping.service.TagService;
import org.sf.web.controller.IBaseController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 标签 controller
 * @author zhuxiao
 */
@RestController
@RequestMapping("tag")
public class TagController extends IBaseController<TagDTO, TagService> {

    public TagController(TagService service) {
        super(service);
    }
}
