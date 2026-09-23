package com.bookkeeping.monthly;

import org.sf.model.response.DataResponse;
import org.springframework.web.bind.annotation.*;

import static com.bookkeeping.monthly.MonthlyReportModels.*;

/**
 * 月报 AI 解读对外接口：配置管理、授权/撤销、连通性测试与手动解读触发。
 * <p>
 * 取代原桌面主进程经 {@code /internal/monthly-report} 的 claim/complete 链路——AI 调用已收归后端，
 * 浏览器/渲染进程仅在用户输入时短暂持有密钥，不持久化；模型调用由后端负责。
 * 控制器统一挂载于 /api 前缀下（见 WebConfig）。
 *
 * @author zhuxiao
 */
@RestController
@RequestMapping("monthly-report/ai")
public class MonthlyReportAiController {
    private final AiConfigService configService;
    private final MonthlyReportAiService aiService;

    public MonthlyReportAiController(AiConfigService configService, MonthlyReportAiService aiService) {
        this.configService = configService;
        this.aiService = aiService;
    }

    /**
     * 读取 AI 配置回显（不含明文密钥）。
     */
    @GetMapping("/config")
    public DataResponse<AiConfigView> config() {
        return DataResponse.of(configService.view());
    }

    /**
     * 保存 AI 配置。
     */
    @PutMapping("/config")
    public DataResponse<AiConfigView> save(@RequestBody AiConfigRequest request) {
        return DataResponse.of(configService.save(request));
    }

    /**
     * 停止当前发送并使已有预览确认失效；clear=true 时一并清除已存密钥。
     */
    @PostMapping("/config/revoke")
    public DataResponse<AiConfigView> revoke(@RequestParam(value = "clear", defaultValue = "false") boolean clear) {
        return DataResponse.of(configService.revoke(clear));
    }

    /**
     * 连通性测试：不发送任何财务数据。
     */
    @PostMapping("/test")
    public DataResponse<Boolean> test(@RequestBody AiConsentRequest request) {
        return DataResponse.of(aiService.testConnection(request));
    }

    /**
     * 单次手动生成，等待 AI 完成并返回已保存的完整月报。
     */
    @PostMapping("/generate")
    public DataResponse<Detail> generate(@RequestBody AiGenerateRequest request) {
        return DataResponse.of(aiService.generate(request));
    }

    /**
     * 确认前预览：按月份计算本次发送的白名单摘要，无需事先生成月报。
     */
    @GetMapping("/preview")
    public DataResponse<AiPreviewView> preview(@RequestParam("month") String month) {
        return DataResponse.of(aiService.preview(month));
    }
}
