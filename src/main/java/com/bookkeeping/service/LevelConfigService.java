package com.bookkeeping.service;

import com.bookkeeping.dao.dto.LevelConfigDTO;
import org.sf.service.IBaseCrudService;
import java.util.Objects;

/**
 * @author
 */
public interface LevelConfigService extends IBaseCrudService<LevelConfigDTO> {

    /**
     * 最小级别需要的经验值
     */
    int MIN_LEVEL_EXP_THRESHOLD = 0;
    /**
     * 最高等级需要的经验值
     */
    int MAX_LEVEL_EXP_THRESHOLD = 19000;

    LevelHolder selectLevelHolder(int level);

    /**
     * 根据新经验获取晋升后的等级
     * @param newExperience 经验
     * @return LevelConfigDTO
     */
    LevelConfigDTO promotionLevel(Integer newExperience);

    LevelConfigDTO selectMaxLevel();

    record LevelHolder(LevelConfigDTO currentDTO, LevelConfigDTO nextDTO){

        public LevelHolder(LevelConfigDTO currentDTO) {
            this(currentDTO, null);
        }

        public int currentLevel() {
            return currentDTO.getLevel();
        }

        public Integer nextLevel() {
            return Objects.isNull(nextDTO) ? null: nextDTO.getLevel();
        }

        public String currentLevelName() {
            return currentDTO.getName();
        }

        public String nextLevelName() {
            return Objects.isNull(nextDTO) ? null : nextDTO.getName();
        }

        public String currentIcon() {
            return currentDTO.getIcon();
        }

        public String currentDescription() {
            return currentDTO.getDescription();
        }

        public Integer currentExpThreshold() {
            return currentDTO.getExpThreshold();
        }

        public Integer nextExpThreshold() {
            return Objects.isNull(nextDTO) ? null : nextDTO.getExpThreshold();
        }
    }
}
