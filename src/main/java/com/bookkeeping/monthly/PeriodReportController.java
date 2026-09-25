package com.bookkeeping.monthly;

import org.sf.model.response.DataResponse;
import org.springframework.web.bind.annotation.*;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import static com.bookkeeping.monthly.MonthlyReportModels.*;

/** 统一报告入口，沿用月报的生成守卫、配置与事务。 */
@RestController
@RequestMapping("ai-report")
public class PeriodReportController {
    private final MonthlyReportService reports;
    private final MonthlyReportAiService ai;
    private final Clock clock;

    public PeriodReportController(MonthlyReportService reports, MonthlyReportAiService ai, Clock clock) {
        this.reports = reports;
        this.ai = ai;
        this.clock = clock;
    }

    @GetMapping
    public DataResponse<List<PeriodEntry>> list(@RequestParam("type") String type,
                                               @RequestParam(value = "year", required = false) Integer year) {
        return DataResponse.of(reports.list(type, year == null ? LocalDate.now(clock).getYear() : year));
    }

    @GetMapping("/{type}/{id}")
    public DataResponse<PeriodDetail> detail(@PathVariable("type") String type, @PathVariable("id") long id) {
        return DataResponse.of(PeriodDetail.from(reports.detail(type, id)));
    }

    @GetMapping("/ai/preview")
    public DataResponse<PeriodPreview> preview(@RequestParam("type") String type, @RequestParam("periodKey") String periodKey) {
        return DataResponse.of(ai.preview(type, periodKey));
    }

    @PostMapping("/ai/generate")
    public DataResponse<PeriodDetail> generate(@RequestBody PeriodGenerateRequest request) {
        return DataResponse.of(ai.generate(request));
    }
}
