package com.bookkeeping.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.bookkeeping.constant.BookkeepingResp;
import com.bookkeeping.dao.mapper.CheckInMapper;
import com.bookkeeping.dao.mapper.PetLogMapper;
import com.bookkeeping.dao.mapper.PetMapper;
import com.bookkeeping.dao.mapping.PetMapping;
import com.bookkeeping.exception.BusinessException;
import com.bookkeeping.service.PetService.PetView;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mapstruct.factory.Mappers;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.sf.dao.config.SelfMetaObjectHandler;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 陪伴宠物心情规则。只连接临时 SQLite，不访问用户账本。 */
class PetServiceImplTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @TempDir
    Path directory;
    private JdbcTemplate jdbc;
    private PetServiceImpl pets;

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource("jdbc:sqlite:" + directory.resolve("pet.db"));
        ResourceDatabasePopulator schema = new ResourceDatabasePopulator(new ClassPathResource("init.sql"));
        schema.setSqlScriptEncoding("UTF-8");
        schema.setContinueOnError(true);
        schema.execute(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("test", new SpringManagedTransactionFactory(), dataSource));
        GlobalConfigUtils.setGlobalConfig(configuration, new GlobalConfig()
                .setDbConfig(new GlobalConfig.DbConfig()).setMetaObjectHandler(new SelfMetaObjectHandler()));
        configuration.addMapper(PetMapper.class);
        configuration.addMapper(PetLogMapper.class);
        configuration.addMapper(CheckInMapper.class);
        SqlSessionFactory factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
        SqlSessionTemplate session = new SqlSessionTemplate(factory);
        pets = new PetServiceImpl(Mappers.getMapper(PetMapping.class));
        ReflectionTestUtils.setField(pets, "baseMapper", session.getMapper(PetMapper.class));
        ReflectionTestUtils.setField(pets, "petLogMapper", session.getMapper(PetLogMapper.class));
        ReflectionTestUtils.setField(pets, "checkInMapper", session.getMapper(CheckInMapper.class));
        clock("2026-10-08T04:00:00Z");
    }

    @Test
    void absentPetIsEmptyAndRecordDoesNothing() {
        PetView view = pets.current();
        assertFalse(view.adopted());
        assertNull(view.name());
        pets.touchRecord();
        assertEquals(0, count("SELECT count(*) FROM t_pet"));
        assertEquals(0, count("SELECT count(*) FROM t_pet_log"));
    }

    @Test
    void adoptRejectsInvalidInputAndASecondPet() {
        assertEquals(BookkeepingResp.PET_INVALID.getCode(),
                assertThrows(BusinessException.class, () -> pets.adopt("  ", "cat")).getCode());
        assertEquals(BookkeepingResp.PET_INVALID.getCode(),
                assertThrows(BusinessException.class, () -> pets.adopt("一二三四五六七八九十一二三", "cat")).getCode());
        assertEquals(BookkeepingResp.PET_INVALID.getCode(),
                assertThrows(BusinessException.class, () -> pets.adopt("小满", "fish")).getCode());
        PetView adopted = pets.adopt("  小满  ", "cat");
        assertTrue(adopted.adopted());
        assertEquals("小满", adopted.name());
        assertEquals("猫", adopted.speciesLabel());
        assertEquals(60, adopted.mood());
        assertEquals("calm", adopted.moodKey());
        assertEquals(1, adopted.stage());
        assertEquals("2026-10-08", adopted.adoptedOn());
        assertEquals(BookkeepingResp.PET_EXISTS.getCode(),
                assertThrows(BusinessException.class, () -> pets.adopt("另一只", "dog")).getCode());
        assertEquals(1, count("SELECT count(*) FROM t_pet"));
    }

    @Test
    void renameUpdatesTheOnlyPet() {
        pets.adopt("小满", "dog");
        assertEquals("团子", pets.rename(" 团子 ").name());
        assertEquals(BookkeepingResp.PET_INVALID.getCode(),
                assertThrows(BusinessException.class, () -> pets.rename("")).getCode());
        assertEquals("团子", pets.current().name());
    }

    @Test
    void checkInLiftsMoodAndStreakChoosesStage() {
        pets.adopt("小满", "bird");
        clock("2026-10-09T04:00:00Z");
        checkIn("2026-10-09", 7);
        PetView view = pets.current();
        assertEquals(68, view.mood());
        assertEquals(7, view.streakDays());
        assertEquals(2, view.stage());
        assertTrue(view.checkedInToday());
        jdbc.update("UPDATE t_check_in SET f_streak_days = 30 WHERE f_check_date = '2026-10-09'");
        assertEquals(3, pets.current().stage());
    }

    @Test
    void missedDaysLowerMoodButThePetStays() {
        pets.adopt("小满", "cat");
        clock("2026-10-12T04:00:00Z");
        PetView sleepy = pets.current();
        assertEquals(24, sleepy.mood());
        assertEquals("sleepy", sleepy.moodKey());
        assertTrue(sleepy.adopted());
        clock("2027-01-01T04:00:00Z");
        PetView floor = pets.current();
        assertEquals(0, floor.mood());
        assertEquals("困倦", floor.moodLabel());
        assertEquals(1, count("SELECT count(*) FROM t_pet"));
    }

    @Test
    void firstRecordOfTheDayAddsMoodOnce() {
        pets.adopt("小满", "cat");
        pets.touchRecord();
        assertEquals(66, pets.current().mood());
        assertTrue(pets.current().recordedToday());
        pets.touchRecord();
        assertEquals(66, pets.current().mood());
        assertEquals(1, count("SELECT count(*) FROM t_pet_log WHERE f_event_type = 'record'"));
    }

    @Test
    void settlingTheSameDayDoesNotChangeMood() {
        pets.adopt("小满", "cat");
        pets.settleToday();
        pets.settleToday();
        assertEquals(60, pets.current().mood());
        assertEquals(0, count("SELECT count(*) FROM t_pet_log WHERE f_event_type = 'settle'"));
    }

    private void clock(String instant) {
        ReflectionTestUtils.setField(pets, "clock", Clock.fixed(Instant.parse(instant), ZONE));
    }

    private void checkIn(String date, int streak) {
        jdbc.update("INSERT INTO t_check_in(f_check_date, f_exp_reward, f_base_exp, f_bonus_exp, f_streak_days) VALUES (?,?,?,?,?)",
                date, "10", "10", "0", streak);
    }

    private int count(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }
}
