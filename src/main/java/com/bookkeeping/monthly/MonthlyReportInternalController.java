package com.bookkeeping.monthly;

import org.sf.model.response.DataResponse;
import org.springframework.web.bind.annotation.*;
import java.time.YearMonth;
import static com.bookkeeping.monthly.MonthlyReportModels.*;

@RestController
@RequestMapping("internal/monthly-report")
public class MonthlyReportInternalController {
    private final MonthlyReportService service;
    private final MonthlyReportRepository repo;
    public MonthlyReportInternalController(MonthlyReportService service, MonthlyReportRepository repo) {
        this.service = service;
        this.repo = repo;
    }
    @GetMapping("/latest")
    public DataResponse<Detail> latest() {
        service.generateLatest();
        StoredReport report = repo.byMonth(YearMonth.now().minusMonths(1).toString());
        return DataResponse.of(report == null ? null : service.detail(report.id()));
    }
    @GetMapping("/{id}")
    public DataResponse<Detail> detail(@PathVariable("id") long id) { return DataResponse.of(service.detail(id)); }
    @PostMapping("/claim")
    public DataResponse<Claim> claim(@RequestBody ClaimRequest request) { return DataResponse.of(service.claim(request)); }
    @PostMapping("/complete")
    public DataResponse<Boolean> complete(@RequestBody Completion request) { return DataResponse.of(service.complete(request)); }
}
