package com.bookkeeping.monthly;

import org.sf.model.response.DataResponse;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.bookkeeping.monthly.MonthlyReportModels.*;

@RestController
@RequestMapping("monthly-report")
public class MonthlyReportController {
    private final MonthlyReportService service;

    public MonthlyReportController(MonthlyReportService service) {
        this.service = service;
    }

    @GetMapping
    public DataResponse<List<MonthEntry>> list(@RequestParam("year") int year) {
        return DataResponse.of(service.list(year));
    }

    @GetMapping("/{id}")
    public DataResponse<Detail> detail(@PathVariable("id") long id) {
        return DataResponse.of(service.detail(id));
    }

}
