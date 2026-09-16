package com.bookkeeping.controller;

import com.bookkeeping.controller.request.TransactionCategoryRequest;
import com.bookkeeping.controller.request.TrendRequest;
import com.bookkeeping.dao.dto.TransactionDTO;
import com.bookkeeping.service.TransactionService;
import org.sf.common.code.CommonResp;
import org.sf.model.request.RangeRequest;
import org.sf.model.response.BaseResponse;
import org.sf.model.response.DataResponse;
import org.sf.web.controller.IBaseController;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 交易 controller
 *
 * @author zhuxiao
 */
@RestController
@RequestMapping("transaction")
public class TransactionController extends IBaseController<TransactionDTO, TransactionService> {

    public TransactionController(TransactionService service) {
        super(service);
    }

    @GetMapping("recent")
    public DataResponse<List<TransactionDTO>> recent() {
        return DataResponse.of(service.recent());
    }

    /**
     * 记账并回传本次经验奖励：与通用 save 同一落库主流程，额外返回发放经验供前端即时反馈。
     * 落库失败返回 INSERT_FAIL（与通用 save 一致的成功/失败语义）；成功时 data 为本次经验（可为 0，如已封顶/满级）。
     */
    @PostMapping("saveWithReward")
    public BaseResponse saveWithReward(@RequestBody TransactionDTO dto) {
        int reward = service.saveWithReward(dto);
        if (reward < 0) {
            return BaseResponse.of(CommonResp.INSERT_FAIL);
        }
        return DataResponse.of(reward);
    }

    /**
     * 对账重算全部账户余额（复位到期初余额 + 重放全部流水），用于修复历史漂移。
     */
    @PostMapping("reconcile")
    public DataResponse<Integer> reconcile() {
        return DataResponse.of(service.reconcileBalances());
    }

    /**
     * 批量删除流水（逐条复用单删的余额冲销，整体事务）。
     */
    @PostMapping("batchDelete")
    public DataResponse<Integer> batchDelete(@RequestParam("ids") List<Integer> ids) {
        return DataResponse.of(service.batchDelete(ids));
    }

    /**
     * 批量修改分类（整体事务，改后重估受影响月份的预算超支）。
     */
    @PostMapping("batchUpdateCategory")
    public DataResponse<Integer> batchUpdateCategory(@RequestParam("ids") List<Integer> ids,
                                                     @RequestParam("categoryId") Integer categoryId) {
        return DataResponse.of(service.batchUpdateCategory(ids, categoryId));
    }

    @PostMapping("stats/summary")
    public DataResponse<TransactionService.SummaryDTO> summary(@RequestBody RangeRequest<LocalDateTime> rangeRequest) {
        return DataResponse.of(service.summary(rangeRequest.getStart(), rangeRequest.getEnd()));
    }

    @PostMapping("stats/trend")
    public DataResponse<List<TransactionService.TrendDTO>> trend(@RequestBody TrendRequest request) {
        return DataResponse.of(service.trend(request.getStart(), request.getEnd(), request.getGranularity()));
    }
    @PostMapping("stats/category")
    public DataResponse<List<TransactionService.TransactionCategoryDTO>> category(@RequestBody TransactionCategoryRequest request) {
        return DataResponse.of(service.category(request.getStart(), request.getEnd(), request.getType(), request.getGroupBy()));
    }

    @PostMapping("stats/monthly")
    public DataResponse<TransactionService.MonthlyDTO> monthly(@RequestBody RangeRequest<LocalDate> rangeRequest) {
        return DataResponse.of(service.monthly(rangeRequest.getStart(), rangeRequest.getEnd()));
    }
}
