package com.bookkeeping.controller;

import com.bookkeeping.dao.dto.BudgetDTO;
import com.bookkeeping.service.BudgetService;
import org.sf.model.response.DataResponse;
import org.sf.web.controller.IBaseController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 预算controller
 * @author zhuxiao
 */
@RestController
@RequestMapping("budget")
public class BudgetController extends IBaseController<BudgetDTO, BudgetService> {

    public BudgetController(BudgetService service) {
        super(service);
    }

    /**
     * 根据年月日查询所有的预算
     * @param year 年
     * @param month 月
     * @return DataResponse<List<BudgetService.BudgetInfo>>
     */
    @PostMapping("searchBudget")
    public DataResponse<List<BudgetService.BudgetInfo>> searchBudget(@RequestParam("year") int year, @RequestParam("month") int month) {
        return DataResponse.of(service.selectBudgetInfoByYearMonth(year, month));
    }
}
