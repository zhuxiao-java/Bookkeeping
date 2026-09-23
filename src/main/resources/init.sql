-- 启用外键约束
PRAGMA foreign_keys = ON;

-- =====================================================
-- 账户表：存储用户的各种资金账户（现金、银行卡等）
-- =====================================================
CREATE TABLE IF NOT EXISTS t_account (
                                         f_id                INTEGER PRIMARY KEY AUTOINCREMENT,  -- 账户唯一ID（自增主键）
                                         f_name              TEXT    NOT NULL,                   -- 账户名称（如：招商银行、微信零钱）
                                         f_type              TEXT    NOT NULL,                   -- 账户类型：cash(现金)、bank(银行卡)、alipay(支付宝)、wechat(微信)等
                                         f_initial_balance   TEXT    DEFAULT '0',                -- 初始余额（精确十进制字符串，如 '100.00'）
                                         f_current_balance   TEXT    DEFAULT '0',                -- 当前余额（由初始余额和交易自动计算）
                                         f_currency          TEXT    DEFAULT 'CNY',              -- 货币代码（默认人民币）
                                         f_is_archived       INTEGER DEFAULT 0,                  -- 是否归档：0-正常，1-已归档（隐藏）
                                         f_create_time        DATETIME DEFAULT CURRENT_TIMESTAMP, -- 创建时间
                                         f_update_time        DATETIME DEFAULT CURRENT_TIMESTAMP  -- 最后更新时间
);

-- =====================================================
-- 分类表：收支分类，支持多级（父子分类）
-- =====================================================
CREATE TABLE IF NOT EXISTS t_category (
    f_id            INTEGER PRIMARY KEY AUTOINCREMENT,      -- 分类唯一ID
    f_name          TEXT    NOT NULL,                       -- 分类名称（如：餐饮、交通、工资）
    f_parent_id     INTEGER REFERENCES t_category(f_id) ON DELETE SET NULL, -- 父分类ID（NULL表示一级分类）
    f_icon          TEXT,                                   -- 分类图标
    f_color         TEXT,                                   -- 分类颜色
    f_type          TEXT    NOT NULL,                       -- 分类类型：income(收入) 或 expense(支出)
    f_sort_order    INTEGER DEFAULT 0,                      -- 排序序号（数值越小越靠前）
    f_is_archived   INTEGER DEFAULT 0,                      -- 是否归档：0-正常，1-已归档
    f_create_time    DATETIME DEFAULT CURRENT_TIMESTAMP,     -- 创建时间
    f_update_time    DATETIME DEFAULT CURRENT_TIMESTAMP      -- 最后更新时间
    );

-- =====================================================
-- 收支记录表：核心交易流水，记录每一笔收入、支出或转账
-- =====================================================
CREATE TABLE IF NOT EXISTS t_transaction (
    f_id            INTEGER PRIMARY KEY AUTOINCREMENT,
    f_type          TEXT    NOT NULL,               -- income / expense / transfer
    f_amount        TEXT    NOT NULL,               -- 金额（转账时为转出金额）
    f_fee           TEXT    DEFAULT '0',            -- 手续费（仅转账时使用，默认0）
    f_account_id    INTEGER NOT NULL REFERENCES t_account(f_id),
    f_to_account_id INTEGER REFERENCES t_account(f_id), -- 转账目标账户（非转账为NULL）
    f_category_id   INTEGER REFERENCES t_category(f_id), -- 收支分类（转账可为NULL）
    f_date          DATE    NOT NULL,
    f_note          TEXT,
    f_tags          TEXT,
    f_create_time    DATETIME DEFAULT CURRENT_TIMESTAMP,
    f_update_time    DATETIME DEFAULT CURRENT_TIMESTAMP
    );

-- =====================================================
-- 预算表：按分类设置的月度预算
-- =====================================================
CREATE TABLE IF NOT EXISTS t_budget (
    f_id            INTEGER PRIMARY KEY AUTOINCREMENT,      -- 预算唯一ID
    f_category_id   INTEGER REFERENCES t_category(f_id) ON DELETE CASCADE, -- 分类ID（NULL表示总预算）
    f_amount        TEXT    NOT NULL,                       -- 预算金额（精确十进制字符串）
    f_month         INTEGER NOT NULL,                       -- 预算月份（1-12）
    f_year          INTEGER NOT NULL,                       -- 预算年份（如2025）
    f_create_time    DATETIME DEFAULT CURRENT_TIMESTAMP,     -- 创建时间
    f_update_time    DATETIME DEFAULT CURRENT_TIMESTAMP,     -- 更新时间
    UNIQUE(f_category_id, f_year, f_month)                  -- 同一分类同一月份唯一预算约束
    );

-- =====================================================
-- 标签表：自定义标签，用于标记交易记录
-- =====================================================
CREATE TABLE IF NOT EXISTS t_tag (
     f_id            INTEGER PRIMARY KEY AUTOINCREMENT,      -- 标签唯一ID
     f_name          TEXT    NOT NULL UNIQUE,                -- 标签名称（唯一）
     f_color         TEXT,                                   -- 标签颜色
     f_create_time    DATETIME DEFAULT CURRENT_TIMESTAMP,      -- 创建时间
     f_update_time    DATETIME DEFAULT CURRENT_TIMESTAMP      -- 最后更新时间
);

CREATE TABLE IF NOT EXISTS t_user_level (
    f_id            INTEGER PRIMARY KEY AUTOINCREMENT,
    f_level         INTEGER NOT NULL DEFAULT 1,         -- 当前等级
    f_experience    INTEGER NOT NULL DEFAULT 0,        -- 当前累计经验值（BigDecimal）
    f_total_earned  TEXT    NOT NULL DEFAULT '0',        -- 累计获得经验
    f_total_spent   TEXT    NOT NULL DEFAULT '0',        -- 累计扣除经验
    f_create_time    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    f_update_time    DATETIME DEFAULT CURRENT_TIMESTAMP,      -- 最后更新时间
    UNIQUE(f_level)
);

-- 生日（用于生日贺卡，格式 yyyy-MM-dd 或 MM-dd）：单机单用户，直接挂在 t_user_level 唯一行上。
-- ALTER ADD COLUMN 在 SQLite 无 IF NOT EXISTS，重复执行会报“duplicate column”，依赖 spring.sql.init.continue-on-error=true 容错（见 application.properties）。
ALTER TABLE t_user_level ADD COLUMN f_birthday TEXT;

CREATE TABLE IF NOT EXISTS t_experience_log (
    f_id            INTEGER PRIMARY KEY AUTOINCREMENT,
    f_year          INTEGER NOT NULL,
    f_month         INTEGER NOT NULL,
    f_budget_amount TEXT    NOT NULL,                   -- 当月预算金额
    f_actual_amount TEXT    NOT NULL,                   -- 当月实际支出金额
    f_diff_amount   TEXT    NOT NULL,                   -- 差额（正节省，负超支）
    f_exp_change    TEXT    NOT NULL,                   -- 经验变动（正加负扣）
    f_create_time    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    f_update_time    DATETIME DEFAULT CURRENT_TIMESTAMP,      -- 最后更新时间
    UNIQUE(f_year, f_month)                             -- 每月仅一条记录
);

CREATE TABLE IF NOT EXISTS t_level_config (
  f_id            INTEGER PRIMARY KEY AUTOINCREMENT,
  f_level         INTEGER NOT NULL UNIQUE,                -- 等级数字（如1,2,3...）
  f_name          TEXT    NOT NULL,                       -- 等级名称（如"理财小白"）
  f_exp_threshold TEXT    NOT NULL,                       -- 达到该等级所需累计经验值（精确十进制）
  f_icon          TEXT,                                   -- 等级图标（可选）
  f_description   TEXT,                                   -- 等级描述
  f_create_time    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  f_update_time    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(f_level, f_name)
);

CREATE TABLE IF NOT EXISTS t_check_in (
  f_id            INTEGER PRIMARY KEY AUTOINCREMENT,
  f_check_date    DATE    NOT NULL UNIQUE,          -- 签到日期（一天仅一条）
  f_exp_reward    TEXT    NOT NULL,                 -- 本次签到获得总经验（基础+额外）
  f_base_exp      TEXT    NOT NULL,                 -- 基础经验
  f_bonus_exp     TEXT    NOT NULL DEFAULT '0',     -- 连续签到额外奖励经验
  f_streak_days   INTEGER NOT NULL DEFAULT 1,       -- 连续签到天数（含本次）
  f_create_time    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  f_update_time    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS t_exp_transaction (
    f_id            INTEGER PRIMARY KEY AUTOINCREMENT,
    f_source        TEXT    NOT NULL,              -- budget / check_in / other
    f_ref_id        INTEGER,                       -- 关联业务表ID
    f_before_experience    TEXT    NOT NULL DEFAULT '0', -- 之前的经验
    f_exp_change    TEXT    NOT NULL,              -- 经验变动值（正负）
    f_description   TEXT,                          -- 描述（如“3月预算节省奖励”）
    f_create_time    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    f_update_time    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- =====================================================
-- 索引：加速常用查询
-- =====================================================
CREATE INDEX IF NOT EXISTS idx_transaction_account_date ON t_transaction(f_account_id, f_date);
CREATE INDEX IF NOT EXISTS idx_transaction_category_date ON t_transaction(f_category_id, f_date);
CREATE INDEX IF NOT EXISTS idx_transaction_date ON t_transaction(f_date);

-- =====================================================
-- 种子数据执行标记表：解决 seed 数据每次启动被重复执行的问题
-- init.sql 以 spring.sql.init.mode=always 每次启动全量执行，DDL 幂等无害，
-- 但 seed 若每次重跑会：①“复活”用户已删除的默认分类/标签/等级配置；
-- ②INSERT OR IGNORE 命中冲突被忽略时仍会消耗 AUTOINCREMENT 序号；③拖慢启动。
-- 故每个 seed 批次整体带「标记不存在」守卫，仅在标记未登记时执行一次，批次末尾登记标记。
-- 后续新增 seed 数据时，请使用新的 f_seed_key（如 'category_v2'）另起一个守卫批次。
-- =====================================================
CREATE TABLE IF NOT EXISTS t_seed_marker (
    f_seed_key    TEXT    PRIMARY KEY,                    -- 种子批次标识
    f_create_time DATETIME DEFAULT CURRENT_TIMESTAMP      -- 该批次实际执行时间
);

INSERT INTO t_user_level (f_level, f_experience, f_total_earned, f_total_spent)
SELECT 1, '0', '0', '0'
    WHERE NOT EXISTS (
    SELECT 1 FROM t_user_level WHERE f_id = 1
);

-- 等级配置 seed：仅 'level_config' 标记不存在时执行一次（OR IGNORE 兜底批次中途异常后的重跑）
INSERT OR IGNORE INTO t_level_config (f_level, f_name, f_exp_threshold, f_icon ,f_description) SELECT 1,  '理财小白',       '0',     'lvl-1','迈出记账第一步' WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'level_config');
INSERT OR IGNORE INTO t_level_config (f_level, f_name, f_exp_threshold, f_icon ,f_description) SELECT 2,  '零钱管家',       '100',   'lvl-2','开始掌控零花钱' WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'level_config');
INSERT OR IGNORE INTO t_level_config (f_level, f_name, f_exp_threshold, f_icon ,f_description) SELECT 3,  '记账新星',       '300',   'lvl-3','坚持记账，初见成效' WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'level_config');
INSERT OR IGNORE INTO t_level_config (f_level, f_name, f_exp_threshold, f_icon ,f_description) SELECT 4,  '预算学徒',       '600',   'lvl-4','学会制定预算' WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'level_config');
INSERT OR IGNORE INTO t_level_config (f_level, f_name, f_exp_threshold, f_icon ,f_description) SELECT 5,  '省钱能手',       '1000',  'lvl-5','节省开支有妙招' WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'level_config');
INSERT OR IGNORE INTO t_level_config (f_level, f_name, f_exp_threshold, f_icon ,f_description) SELECT 6,  '精明消费者',     '1500',  'lvl-6','消费决策更理性' WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'level_config');
INSERT OR IGNORE INTO t_level_config (f_level, f_name, f_exp_threshold, f_icon ,f_description) SELECT 7,  '储蓄达人',       '2100',  'lvl-7','存款稳步增长' WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'level_config');
INSERT OR IGNORE INTO t_level_config (f_level, f_name, f_exp_threshold, f_icon ,f_description) SELECT 8,  '财务规划师',     '2800',  'lvl-8','开始规划未来' WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'level_config');
INSERT OR IGNORE INTO t_level_config (f_level, f_name, f_exp_threshold, f_icon ,f_description) SELECT 9,  '投资观察员',     '3600',  'lvl-9','关注资产增值' WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'level_config');
INSERT OR IGNORE INTO t_level_config (f_level, f_name, f_exp_threshold, f_icon ,f_description) SELECT 10, '财富积累者',     '4500',  'lvl-10','资产雪球滚起来' WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'level_config');
INSERT OR IGNORE INTO t_level_config (f_level, f_name, f_exp_threshold, f_icon ,f_description) SELECT 11, '理财高手',       '5500',  'lvl-11','收支平衡游刃有余' WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'level_config');
INSERT OR IGNORE INTO t_level_config (f_level, f_name, f_exp_threshold, f_icon ,f_description) SELECT 12, '预算大师',       '6600',  'lvl-12','预算管理炉火纯青' WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'level_config');
INSERT OR IGNORE INTO t_level_config (f_level, f_name, f_exp_threshold, f_icon ,f_description) SELECT 13, '财务自由预备役', '7800',  'lvl-13','离目标越来越近' WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'level_config');
INSERT OR IGNORE INTO t_level_config (f_level, f_name, f_exp_threshold, f_icon ,f_description) SELECT 14, '资产管理者',     '9100',  'lvl-14','全面掌控资产' WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'level_config');
INSERT OR IGNORE INTO t_level_config (f_level, f_name, f_exp_threshold, f_icon ,f_description) SELECT 15, '财富规划专家',   '10500', 'lvl-15','为长远目标布局' WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'level_config');
INSERT OR IGNORE INTO t_level_config (f_level, f_name, f_exp_threshold, f_icon ,f_description) SELECT 16, '金钱掌控者',     '12000', 'lvl-16','金钱为你工作' WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'level_config');
INSERT OR IGNORE INTO t_level_config (f_level, f_name, f_exp_threshold, f_icon ,f_description) SELECT 17, '财务自由人',     '13600', 'lvl-17','被动收入覆盖支出' WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'level_config');
INSERT OR IGNORE INTO t_level_config (f_level, f_name, f_exp_threshold, f_icon ,f_description) SELECT 18, '财富领航员',     '15300', 'lvl-18','引领他人走向财务健康' WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'level_config');
INSERT OR IGNORE INTO t_level_config (f_level, f_name, f_exp_threshold, f_icon ,f_description) SELECT 19, '理财传奇',       '17100', 'lvl-19','经验丰富，值得信赖' WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'level_config');
INSERT OR IGNORE INTO t_level_config (f_level, f_name, f_exp_threshold, f_icon ,f_description) SELECT 20, '财务之神',       '19000', 'lvl-20','登峰造极，掌控金钱' WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'level_config');
INSERT OR IGNORE INTO t_seed_marker (f_seed_key) VALUES ('level_config');
-- 历史 seed 中 12 级图标误写为 'lvl=12'，标记机制生效后 seed 不再重跑，故用幂等 UPDATE 修正存量库
UPDATE t_level_config SET f_icon = 'lvl-12' WHERE f_level = 12 AND f_icon = 'lvl=12';

-- =====================================================
-- 默认分类 seed（GAP-06）
-- init.sql 以 spring.sql.init.mode=always 每次启动执行，故 seed 由 t_seed_marker 'category' 标记守卫，仅执行一次：
--   ① f_name 已按业务规则全局唯一（CategoryServiceImpl.savePreCheck），加唯一索引使 INSERT OR IGNORE 可去重（兜底批次中途异常后的重跑）；
--   ② 图标名取自前端 constants.ts CATEGORY_ICON_GROUPS，颜色取自 COLOR_PALETTE，同类型内一级分类颜色互不重复；
--   ③ 子分类默认继承父分类颜色（符合前端颜色占用约定），f_parent_id 用子查询按父名解析，故父分类须先插入。
-- =====================================================
CREATE UNIQUE INDEX IF NOT EXISTS idx_category_name ON t_category(f_name);

-- 支出一级分类
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '餐饮', NULL, 'food',      '#F56C6C', 'expense', 1 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '交通', NULL, 'transport', '#E6A23C', 'expense', 2 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '购物', NULL, 'shopping',  '#FA8C16', 'expense', 3 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '居住', NULL, 'home',      '#409EFF', 'expense', 4 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '娱乐', NULL, 'fun',       '#9B59B6', 'expense', 5 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '医疗', NULL, 'medical',   '#00B8A9', 'expense', 6 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '教育', NULL, 'edu',       '#67C23A', 'expense', 7 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '通讯', NULL, 'internet',  '#7B68EE', 'expense', 8 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');

-- 支出二级分类（继承父色）
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '早餐', (SELECT f_id FROM t_category WHERE f_name='餐饮'), 'rice',    '#F56C6C', 'expense', 1 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '午餐', (SELECT f_id FROM t_category WHERE f_name='餐饮'), 'food',    '#F56C6C', 'expense', 2 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '晚餐', (SELECT f_id FROM t_category WHERE f_name='餐饮'), 'hotpot',  '#F56C6C', 'expense', 3 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '外卖', (SELECT f_id FROM t_category WHERE f_name='餐饮'), 'takeout', '#F56C6C', 'expense', 4 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '水果', (SELECT f_id FROM t_category WHERE f_name='餐饮'), 'fruit',   '#F56C6C', 'expense', 5 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '饮品', (SELECT f_id FROM t_category WHERE f_name='餐饮'), 'coffee',  '#F56C6C', 'expense', 6 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '打车', (SELECT f_id FROM t_category WHERE f_name='交通'), 'taxi',     '#E6A23C', 'expense', 1 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '公交', (SELECT f_id FROM t_category WHERE f_name='交通'), 'bus',      '#E6A23C', 'expense', 2 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '地铁', (SELECT f_id FROM t_category WHERE f_name='交通'), 'subway',   '#E6A23C', 'expense', 3 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '加油', (SELECT f_id FROM t_category WHERE f_name='交通'), 'fuel',     '#E6A23C', 'expense', 4 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '停车', (SELECT f_id FROM t_category WHERE f_name='交通'), 'parking',  '#E6A23C', 'expense', 5 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '服饰', (SELECT f_id FROM t_category WHERE f_name='购物'), 'clothes',    '#FA8C16', 'expense', 1 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '数码', (SELECT f_id FROM t_category WHERE f_name='购物'), 'digital',    '#FA8C16', 'expense', 2 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '日用', (SELECT f_id FROM t_category WHERE f_name='购物'), 'daily',      '#FA8C16', 'expense', 3 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '超市', (SELECT f_id FROM t_category WHERE f_name='购物'), 'supermarket','#FA8C16', 'expense', 4 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '房租', (SELECT f_id FROM t_category WHERE f_name='居住'), 'rent',     '#409EFF', 'expense', 1 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '水电', (SELECT f_id FROM t_category WHERE f_name='居住'), 'electric', '#409EFF', 'expense', 2 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '燃气', (SELECT f_id FROM t_category WHERE f_name='居住'), 'gas',      '#409EFF', 'expense', 3 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '宽带', (SELECT f_id FROM t_category WHERE f_name='居住'), 'internet', '#409EFF', 'expense', 4 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '物业', (SELECT f_id FROM t_category WHERE f_name='居住'), 'property', '#409EFF', 'expense', 5 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '电影', (SELECT f_id FROM t_category WHERE f_name='娱乐'), 'movie',  '#9B59B6', 'expense', 1 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '运动', (SELECT f_id FROM t_category WHERE f_name='娱乐'), 'sport',  '#9B59B6', 'expense', 2 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '旅行', (SELECT f_id FROM t_category WHERE f_name='娱乐'), 'travel', '#9B59B6', 'expense', 3 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '音乐', (SELECT f_id FROM t_category WHERE f_name='娱乐'), 'music',  '#9B59B6', 'expense', 4 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '就诊', (SELECT f_id FROM t_category WHERE f_name='医疗'), 'hospital', '#00B8A9', 'expense', 1 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '药品', (SELECT f_id FROM t_category WHERE f_name='医疗'), 'medical',  '#00B8A9', 'expense', 2 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '体检', (SELECT f_id FROM t_category WHERE f_name='医疗'), 'checkup',  '#00B8A9', 'expense', 3 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '牙科', (SELECT f_id FROM t_category WHERE f_name='医疗'), 'dental',   '#00B8A9', 'expense', 4 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '课程', (SELECT f_id FROM t_category WHERE f_name='教育'), 'course', '#67C23A', 'expense', 1 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '书籍', (SELECT f_id FROM t_category WHERE f_name='教育'), 'book',   '#67C23A', 'expense', 2 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '培训', (SELECT f_id FROM t_category WHERE f_name='教育'), 'school', '#67C23A', 'expense', 3 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '话费', (SELECT f_id FROM t_category WHERE f_name='通讯'), 'membership', '#7B68EE', 'expense', 1 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');

-- 收入一级分类
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '工资', NULL, 'salary',   '#67C23A', 'income', 1 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '奖金', NULL, 'bonus',    '#F56C6C', 'income', 2 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '理财', NULL, 'invest',   '#409EFF', 'income', 3 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '兼职', NULL, 'parttime', '#E6A23C', 'income', 4 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '红包', NULL, 'redpacket','#E91E63', 'income', 5 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');

-- 收入二级分类（继承父色）
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '基本工资', (SELECT f_id FROM t_category WHERE f_name='工资'), 'salary',   '#67C23A', 'income', 1 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '绩效', (SELECT f_id FROM t_category WHERE f_name='工资'), 'bonus',    '#67C23A', 'income', 2 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '利息', (SELECT f_id FROM t_category WHERE f_name='理财'), 'interest', '#409EFF', 'income', 1 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '基金', (SELECT f_id FROM t_category WHERE f_name='理财'), 'invest',   '#409EFF', 'income', 2 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');

-- =====================================================
-- 扩展分类 seed（提升开箱即用体验）
-- 同样幂等（INSERT OR IGNORE + idx_category_name 全局唯一）；名称已逐一避让现有分类，
-- 如用「利息支出/车辆维修/房屋维修」而非「利息/维修」，防止全局唯一名冲突被静默忽略。
-- 新一级分类颜色取自高区分度色池（DISTINCT_COLORS），色相避开现有支出一级分类；子分类继承父色。
-- 图标名均取自前端 constants.ts CATEGORY_ICON_GROUPS。
-- =====================================================

-- 新增支出一级分类（sort 续 9~14）
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '人情',     NULL, 'gift',      '#D37295', 'expense', 9 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '宠物',     NULL, 'pet',       '#9C755F', 'expense', 10 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '美妆个护', NULL, 'cosmetics', '#FF9DA7', 'expense', 11 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '汽车',     NULL, 'maintenance','#B6992D', 'expense', 12 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '金融保险', NULL, 'insurance', '#499894', 'expense', 13 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '母婴亲子', NULL, 'baby',      '#EDC948', 'expense', 14 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');

-- 新增支出一级分类的子分类（继承父色）
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '随礼',     (SELECT f_id FROM t_category WHERE f_name='人情'), 'wedding',   '#D37295', 'expense', 1 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '礼物',     (SELECT f_id FROM t_category WHERE f_name='人情'), 'gift',      '#D37295', 'expense', 2 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '请客',     (SELECT f_id FROM t_category WHERE f_name='人情'), 'party',     '#D37295', 'expense', 3 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '孝敬父母', (SELECT f_id FROM t_category WHERE f_name='人情'), 'parents',   '#D37295', 'expense', 4 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '捐赠',     (SELECT f_id FROM t_category WHERE f_name='人情'), 'donate',    '#D37295', 'expense', 5 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '红包支出', (SELECT f_id FROM t_category WHERE f_name='人情'), 'redpacket', '#D37295', 'expense', 6 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '宠物食品', (SELECT f_id FROM t_category WHERE f_name='宠物'), 'food',      '#9C755F', 'expense', 1 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '宠物用品', (SELECT f_id FROM t_category WHERE f_name='宠物'), 'daily',     '#9C755F', 'expense', 2 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '宠物医疗', (SELECT f_id FROM t_category WHERE f_name='宠物'), 'medical',   '#9C755F', 'expense', 3 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '护肤',     (SELECT f_id FROM t_category WHERE f_name='美妆个护'), 'cosmetics', '#FF9DA7', 'expense', 1 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '彩妆',     (SELECT f_id FROM t_category WHERE f_name='美妆个护'), 'cosmetics', '#FF9DA7', 'expense', 2 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '个人护理', (SELECT f_id FROM t_category WHERE f_name='美妆个护'), 'daily',     '#FF9DA7', 'expense', 3 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '保养',     (SELECT f_id FROM t_category WHERE f_name='汽车'), 'maintenance', '#B6992D', 'expense', 1 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '车辆维修', (SELECT f_id FROM t_category WHERE f_name='汽车'), 'repair-home', '#B6992D', 'expense', 2 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '车险',     (SELECT f_id FROM t_category WHERE f_name='汽车'), 'insurance',   '#B6992D', 'expense', 3 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '洗车',     (SELECT f_id FROM t_category WHERE f_name='汽车'), 'clean',       '#B6992D', 'expense', 4 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '保险',     (SELECT f_id FROM t_category WHERE f_name='金融保险'), 'insurance',  '#499894', 'expense', 1 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '手续费',   (SELECT f_id FROM t_category WHERE f_name='金融保险'), 'loan',       '#499894', 'expense', 2 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '利息支出', (SELECT f_id FROM t_category WHERE f_name='金融保险'), 'interest',   '#499894', 'expense', 3 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '税费',     (SELECT f_id FROM t_category WHERE f_name='金融保险'), 'government', '#499894', 'expense', 4 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '奶粉',     (SELECT f_id FROM t_category WHERE f_name='母婴亲子'), 'milk',   '#EDC948', 'expense', 1 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '玩具',     (SELECT f_id FROM t_category WHERE f_name='母婴亲子'), 'toy',    '#EDC948', 'expense', 2 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '童装',     (SELECT f_id FROM t_category WHERE f_name='母婴亲子'), 'clothes','#EDC948', 'expense', 3 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '早教',     (SELECT f_id FROM t_category WHERE f_name='母婴亲子'), 'course', '#EDC948', 'expense', 4 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');

-- 补充现有支出一级分类的常用子分类（继承各自父色）
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '夜宵',   (SELECT f_id FROM t_category WHERE f_name='餐饮'), 'bbq',    '#F56C6C', 'expense', 7 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '甜点',   (SELECT f_id FROM t_category WHERE f_name='餐饮'), 'cake',   '#F56C6C', 'expense', 8 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '零食',   (SELECT f_id FROM t_category WHERE f_name='餐饮'), 'snack',  '#F56C6C', 'expense', 9 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '聚餐',   (SELECT f_id FROM t_category WHERE f_name='餐饮'), 'hotpot', '#F56C6C', 'expense', 10 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '酒水',   (SELECT f_id FROM t_category WHERE f_name='餐饮'), 'wine',   '#F56C6C', 'expense', 11 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '火车',   (SELECT f_id FROM t_category WHERE f_name='交通'), 'train',  '#E6A23C', 'expense', 6 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '飞机',   (SELECT f_id FROM t_category WHERE f_name='交通'), 'flight', '#E6A23C', 'expense', 7 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '高速费', (SELECT f_id FROM t_category WHERE f_name='交通'), 'toll',   '#E6A23C', 'expense', 8 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '共享单车',(SELECT f_id FROM t_category WHERE f_name='交通'), 'bike',   '#E6A23C', 'expense', 9 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '家电',   (SELECT f_id FROM t_category WHERE f_name='购物'), 'appliance', '#FA8C16', 'expense', 5 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '家居',   (SELECT f_id FROM t_category WHERE f_name='购物'), 'furniture', '#FA8C16', 'expense', 6 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '网购',   (SELECT f_id FROM t_category WHERE f_name='购物'), 'ecommerce', '#FA8C16', 'expense', 7 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '鞋包',   (SELECT f_id FROM t_category WHERE f_name='购物'), 'shoes',       '#FA8C16', 'expense', 8 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '文具',   (SELECT f_id FROM t_category WHERE f_name='购物'), 'stationery',  '#FA8C16', 'expense', 9 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '保洁',   (SELECT f_id FROM t_category WHERE f_name='居住'), 'clean',       '#409EFF', 'expense', 6 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '房屋维修',(SELECT f_id FROM t_category WHERE f_name='居住'), 'repair-home', '#409EFF', 'expense', 7 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '游戏',   (SELECT f_id FROM t_category WHERE f_name='娱乐'), 'fun',     '#9B59B6', 'expense', 5 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT 'KTV',    (SELECT f_id FROM t_category WHERE f_name='娱乐'), 'karaoke', '#9B59B6', 'expense', 6 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '聚会',   (SELECT f_id FROM t_category WHERE f_name='娱乐'), 'party',   '#9B59B6', 'expense', 7 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '摄影',   (SELECT f_id FROM t_category WHERE f_name='娱乐'), 'photo',   '#9B59B6', 'expense', 8 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '疫苗',   (SELECT f_id FROM t_category WHERE f_name='医疗'), 'vaccine', '#00B8A9', 'expense', 5 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '保健品', (SELECT f_id FROM t_category WHERE f_name='医疗'), 'medical', '#00B8A9', 'expense', 6 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '考试报名',(SELECT f_id FROM t_category WHERE f_name='教育'), 'school',  '#67C23A', 'expense', 4 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '会员订阅',(SELECT f_id FROM t_category WHERE f_name='通讯'), 'membership','#7B68EE','expense', 2 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '流量充值',(SELECT f_id FROM t_category WHERE f_name='通讯'), 'internet',  '#7B68EE','expense', 3 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');

-- 新增收入一级分类（sort 续 6~8；颜色避开现有收入一级分类色相）
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '报销',     NULL, 'refund',      '#B07AA1', 'income', 6 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '租金收入', NULL, 'rent-income', '#76B7B2', 'income', 7 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '中奖',     NULL, 'lottery',     '#EDC948', 'income', 8 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');

-- 新增/补充收入子分类（继承父色）
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '差旅报销', (SELECT f_id FROM t_category WHERE f_name='报销'), 'refund',  '#B07AA1', 'income', 1 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '医疗报销', (SELECT f_id FROM t_category WHERE f_name='报销'), 'medical', '#B07AA1', 'income', 2 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '私活',     (SELECT f_id FROM t_category WHERE f_name='兼职'), 'parttime',  '#E6A23C', 'income', 1 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '副业',     (SELECT f_id FROM t_category WHERE f_name='兼职'), 'parttime',  '#E6A23C', 'income', 2 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '微信红包', (SELECT f_id FROM t_category WHERE f_name='红包'), 'redpacket', '#E91E63', 'income', 1 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '节日红包', (SELECT f_id FROM t_category WHERE f_name='红包'), 'redpacket', '#E91E63', 'income', 2 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '股票',     (SELECT f_id FROM t_category WHERE f_name='理财'), 'invest',   '#409EFF', 'income', 3 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) SELECT '分红',     (SELECT f_id FROM t_category WHERE f_name='理财'), 'interest', '#409EFF', 'income', 4 WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'category');
-- 分类 seed 批次完成，登记标记；后续启动整批跳过，不再复活用户已删除的默认分类
INSERT OR IGNORE INTO t_seed_marker (f_seed_key) VALUES ('category');

-- =====================================================
-- 默认标签 seed（提升开箱体验）
-- 同分类 seed，由 t_seed_marker 'tag' 标记守卫仅执行一次；t_tag.f_name 表级 UNIQUE 兜底批次中途异常后的重跑；
-- 已存在的同名标签（如用户自建的「外卖」）保留其原色不被覆盖。
-- 标签是跨分类的标记维度，故取「报销/出差/刚需/冲动消费」等横切语义；颜色取自高区分度色池，逐个互不相同。
-- =====================================================
INSERT OR IGNORE INTO t_tag (f_name, f_color) SELECT '报销',     '#E15759' WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'tag');
INSERT OR IGNORE INTO t_tag (f_name, f_color) SELECT '出差',     '#4E79A7' WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'tag');
INSERT OR IGNORE INTO t_tag (f_name, f_color) SELECT '旅游',     '#59A14F' WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'tag');
INSERT OR IGNORE INTO t_tag (f_name, f_color) SELECT '网购',     '#F28E2B' WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'tag');
INSERT OR IGNORE INTO t_tag (f_name, f_color) SELECT '聚餐',     '#B07AA1' WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'tag');
INSERT OR IGNORE INTO t_tag (f_name, f_color) SELECT '送礼',     '#76B7B2' WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'tag');
INSERT OR IGNORE INTO t_tag (f_name, f_color) SELECT '缴费',     '#EDC948' WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'tag');
INSERT OR IGNORE INTO t_tag (f_name, f_color) SELECT '刚需',     '#9C755F' WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'tag');
INSERT OR IGNORE INTO t_tag (f_name, f_color) SELECT '冲动消费', '#FF9DA7' WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'tag');
INSERT OR IGNORE INTO t_tag (f_name, f_color) SELECT '优惠',     '#86BCB6' WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'tag');
INSERT OR IGNORE INTO t_tag (f_name, f_color) SELECT '存钱',     '#8CD17D' WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'tag');
INSERT OR IGNORE INTO t_tag (f_name, f_color) SELECT '分摊',     '#D37295' WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'tag');
INSERT OR IGNORE INTO t_tag (f_name, f_color) SELECT '待还',     '#A0CBE8' WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'tag');
INSERT OR IGNORE INTO t_tag (f_name, f_color) SELECT '学习',     '#F1CE63' WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'tag');
INSERT OR IGNORE INTO t_tag (f_name, f_color) SELECT '健身',     '#B6992D' WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'tag');
INSERT OR IGNORE INTO t_tag (f_name, f_color) SELECT '家庭',     '#499894' WHERE NOT EXISTS (SELECT 1 FROM t_seed_marker WHERE f_seed_key = 'tag');
INSERT OR IGNORE INTO t_seed_marker (f_seed_key) VALUES ('tag');

CREATE TABLE IF NOT EXISTS t_message (
     f_id            INTEGER PRIMARY KEY AUTOINCREMENT,
     f_title         TEXT    NOT NULL,                       -- 消息标题（如“预算超支提醒”）
     f_content       TEXT    NOT NULL,                       -- 消息内容（纯文本或HTML，建议纯文本）
     f_type          TEXT    NOT NULL,                       -- 消息类型：system(系统通知)、budget(预算提醒)、level(等级通知)、check_in(签到提醒)、greeting(贺卡)等
     f_biz_type      TEXT,                                   -- 关联业务类型（可选）：transaction、budget、level 等，便于跳转
     f_card_image    TEXT,                                   -- 贺卡图片
     f_biz_id        INTEGER,                                -- 关联业务ID（可选），如超支的预算ID、触发等级变动的日志ID
     f_status        INTEGER NOT NULL DEFAULT 0,             -- 是否已读：0-未读，1-已读
     f_create_time   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, -- 发送时间
     f_update_time   DATETIME DEFAULT CURRENT_TIMESTAMP  -- 更新时间,也可以理解为阅读时间
);

-- 索引：加速未读消息查询
CREATE INDEX IF NOT EXISTS idx_message_read ON t_message(f_status, f_create_time DESC);

-- 仅在 AI 成功后原子保存月报；启动脚本不创建月报或模型请求。
CREATE TABLE IF NOT EXISTS t_monthly_report (
    f_id INTEGER PRIMARY KEY AUTOINCREMENT,
    f_month TEXT NOT NULL UNIQUE,
    f_version INTEGER NOT NULL,
    f_source_hash TEXT NOT NULL,
    f_snapshot TEXT NOT NULL,
    f_generated_at TEXT NOT NULL,
    f_message_id INTEGER
);
-- 兼容旧库；重复启动的重复列异常由初始化容错处理，不覆盖已有结果。
ALTER TABLE t_monthly_report ADD COLUMN f_ai_result TEXT;

-- 月报 AI 解读配置（单用户桌面应用，固定一行 f_id=1）。
-- 说明：AI 解读已由后端 AgentScope 直接发起，密钥随本地库保存（与桌面单机风险面相当），回显接口不返回明文 key。
CREATE TABLE IF NOT EXISTS t_ai_config (
    f_id INTEGER PRIMARY KEY,
    f_base_url TEXT NOT NULL DEFAULT '',
    f_model TEXT NOT NULL DEFAULT '',
    f_api_key TEXT NOT NULL DEFAULT '',
    f_include_names INTEGER NOT NULL DEFAULT 0,
    f_config_version TEXT NOT NULL,
    f_update_time TEXT NOT NULL
);