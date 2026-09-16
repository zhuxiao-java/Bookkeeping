package com.bookkeeping.controller;

import com.bookkeeping.dao.dto.ExperienceLogDTO;
import com.bookkeeping.dao.dto.LevelConfigDTO;
import com.bookkeeping.dao.dto.UserLevelDTO;
import com.bookkeeping.service.ExperienceLogService;
import com.bookkeeping.service.LevelConfigService;
import com.bookkeeping.service.UserLevelService;
import lombok.AllArgsConstructor;
import org.sf.model.response.BaseResponse;
import org.sf.model.response.DataResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户等级Controller
 *
 * @author zhuxiao
 */
@RestController
@RequestMapping("level")
@AllArgsConstructor
public class LevelController {

    private final UserLevelService userLevelService;

    private final LevelConfigService levelConfigService;

    private final ExperienceLogService experienceLogService;

    public record LevelInfo(int level,
                            Integer experience,
                            Integer totalEarned,
                            Integer totalSpent,
                            Integer currentThreshold,
                            String description,
                            String leveName,
                            String icon,
                            Integer nextLevel,
                            String nextLevelName,
                            Integer nextThreshold,
                            String birthday) {
    }

    @GetMapping("currentLevel")
    public DataResponse<LevelInfo> currentLevel() {
        UserLevelDTO dto = userLevelService.selectUserLevel();
        LevelConfigService.LevelHolder holder = levelConfigService.selectLevelHolder(dto.getLevel());
        return DataResponse.of(new LevelInfo(
                dto.getLevel(),
                dto.getExperience(),
                dto.getTotalEarned(),
                dto.getTotalSpent(),
                holder.currentExpThreshold(),
                holder.currentDescription(),
                holder.currentLevelName(),
                holder.currentIcon(),
                holder.nextLevel(),
                holder.nextLevelName(),
                holder.nextExpThreshold(),
                dto.getBirthday()
        ));
    }

    /**
     * 设置生日（yyyy-MM-dd 或 MM-dd）；传空串则清除。
     */
    @PostMapping("birthday")
    public BaseResponse setBirthday(@RequestParam(value = "birthday", required = false) String birthday) {
        userLevelService.updateBirthday(birthday);
        return BaseResponse.success();
    }

    @GetMapping("configs")
    public DataResponse<List<LevelConfigDTO>> configs() {
        List<LevelConfigDTO> dtoList = levelConfigService.selectAll();
        return DataResponse.of(dtoList);
    }

    @GetMapping("logs")
    public DataResponse<List<ExperienceLogDTO>> logs() {
        List<ExperienceLogDTO> list = experienceLogService.selectAll();
        return DataResponse.of(list);
    }
}
