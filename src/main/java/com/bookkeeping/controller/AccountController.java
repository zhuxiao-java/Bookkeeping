package com.bookkeeping.controller;

import com.bookkeeping.dao.dto.AccountDTO;
import com.bookkeeping.service.AccountService;
import org.sf.web.controller.IBaseController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 账户controller
 * @author zhuxiao
 */
@RestController
@RequestMapping("account")
public class AccountController extends IBaseController<AccountDTO, AccountService> {

    public AccountController(AccountService service) {
        super(service);
    }
}
