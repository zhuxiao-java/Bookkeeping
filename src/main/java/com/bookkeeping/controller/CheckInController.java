package com.bookkeeping.controller;

import com.bookkeeping.dao.dto.CheckInDTO;
import com.bookkeeping.service.CheckInService;
import org.sf.web.controller.IBaseController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 签到 controller
 * @author zhuxiao
 */
@RestController
@RequestMapping("check_in")
public class CheckInController extends IBaseController<CheckInDTO, CheckInService> {
    
    public CheckInController(CheckInService service) {
        super(service);
    }
}
