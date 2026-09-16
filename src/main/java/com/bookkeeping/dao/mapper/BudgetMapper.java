package com.bookkeeping.dao.mapper;

import com.bookkeeping.dao.entity.BudgetEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.sf.dao.mapper.IBaseMapper;

import java.math.BigDecimal;

/**
 * 预算mapper
 * @author zx
 */
public interface BudgetMapper extends IBaseMapper<BudgetEntity> {
    @Select("select sum(f_amount) from t_budget where f_year = #{year} and f_month = #{month} and f_category_id is not null")
    BigDecimal searchCategoryBudgetSum(@Param("year") int year, @Param("month") int month);
}
