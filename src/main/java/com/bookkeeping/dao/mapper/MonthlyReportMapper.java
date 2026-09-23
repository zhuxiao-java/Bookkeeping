package com.bookkeeping.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bookkeeping.dao.entity.MonthlyReportEntity;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Param;
import java.util.List;

/**
 * 月报快照 mapper。
 * <p>
 * 实体不继承框架 BaseEntity，故直接继承 MyBatis-Plus {@link BaseMapper}，
 * 通用增删改查由 BaseMapper 提供，复杂统计口径在 service 层用 QueryWrapper 组合完成。
 *
 * @author zhuxiao
 */
public interface MonthlyReportMapper extends BaseMapper<MonthlyReportEntity> {

    /**
     * 全库最早一条流水所在月份（yyyy-MM），用于判断历史是否足够、是否输出三月均值。
     *
     * @return 形如 2026-01 的月份字符串；无任何流水时返回 null
     */
    @Select("SELECT substr(MIN(f_date), 1, 7) FROM t_transaction")
    String firstTransactionMonth();

    /** 仅供启动兼容迁移，正常业务不使用旧作业表。 */
    @Select("SELECT EXISTS(SELECT 1 FROM sqlite_master WHERE type='table' AND name='t_monthly_report_ai_job')")
    boolean hasLegacyJobs();

    @Select("SELECT EXISTS(SELECT 1 FROM pragma_table_info('t_ai_config') WHERE name='f_automatic')")
    boolean hasLegacyAutomatic();

    @Update("UPDATE t_ai_config SET f_automatic=0 WHERE f_automatic<>0")
    void clearLegacyAutomatic();

    @Select("SELECT f_result FROM t_monthly_report_ai_job WHERE f_report_id=#{id} AND f_snapshot_version=#{version} "
            + "AND f_status='succeeded' AND f_result IS NOT NULL ORDER BY rowid DESC")
    List<String> legacyResults(@Param("id") int id, @Param("version") int version);
}
