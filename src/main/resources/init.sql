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
    f_experience    TEXT    NOT NULL DEFAULT '0',        -- 当前累计经验值（BigDecimal）
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

INSERT INTO t_user_level (f_level, f_experience, f_total_earned, f_total_spent)
SELECT 1, '0', '0', '0'
    WHERE NOT EXISTS (
    SELECT 1 FROM t_user_level WHERE f_id = 1
);

INSERT OR IGNORE INTO t_level_config (f_level, f_name, f_exp_threshold, f_icon ,f_description) VALUES (1,  '理财小白',       '0',     'lvl-1','迈出记账第一步');
INSERT OR IGNORE INTO t_level_config (f_level, f_name, f_exp_threshold, f_icon ,f_description) VALUES (2,  '零钱管家',       '100',   'lvl-2','开始掌控零花钱');
INSERT OR IGNORE INTO t_level_config (f_level, f_name, f_exp_threshold, f_icon ,f_description) VALUES (3,  '记账新星',       '300',   'lvl-3','坚持记账，初见成效');
INSERT OR IGNORE INTO t_level_config (f_level, f_name, f_exp_threshold, f_icon ,f_description) VALUES (4,  '预算学徒',       '600',   'lvl-4','学会制定预算');
INSERT OR IGNORE INTO t_level_config (f_level, f_name, f_exp_threshold, f_icon ,f_description) VALUES (5,  '省钱能手',       '1000',  'lvl-5','节省开支有妙招');
INSERT OR IGNORE INTO t_level_config (f_level, f_name, f_exp_threshold, f_icon ,f_description) VALUES (6,  '精明消费者',     '1500',  'lvl-6','消费决策更理性');
INSERT OR IGNORE INTO t_level_config (f_level, f_name, f_exp_threshold, f_icon ,f_description) VALUES (7,  '储蓄达人',       '2100',  'lvl-7','存款稳步增长');
INSERT OR IGNORE INTO t_level_config (f_level, f_name, f_exp_threshold, f_icon ,f_description) VALUES (8,  '财务规划师',     '2800',  'lvl-8','开始规划未来');
INSERT OR IGNORE INTO t_level_config (f_level, f_name, f_exp_threshold, f_icon ,f_description) VALUES (9,  '投资观察员',     '3600',  'lvl-9','关注资产增值');
INSERT OR IGNORE INTO t_level_config (f_level, f_name, f_exp_threshold, f_icon ,f_description) VALUES (10, '财富积累者',     '4500',  'lvl-10','资产雪球滚起来');
INSERT OR IGNORE INTO t_level_config (f_level, f_name, f_exp_threshold, f_icon ,f_description) VALUES (11, '理财高手',       '5500',  'lvl-11','收支平衡游刃有余');
INSERT OR IGNORE INTO t_level_config (f_level, f_name, f_exp_threshold, f_icon ,f_description) VALUES (12, '预算大师',       '6600',  'lvl=12','预算管理炉火纯青');
INSERT OR IGNORE INTO t_level_config (f_level, f_name, f_exp_threshold, f_icon ,f_description) VALUES (13, '财务自由预备役', '7800',  'lvl-13','离目标越来越近');
INSERT OR IGNORE INTO t_level_config (f_level, f_name, f_exp_threshold, f_icon ,f_description) VALUES (14, '资产管理者',     '9100',  'lvl-14','全面掌控资产');
INSERT OR IGNORE INTO t_level_config (f_level, f_name, f_exp_threshold, f_icon ,f_description) VALUES (15, '财富规划专家',   '10500', 'lvl-15','为长远目标布局');
INSERT OR IGNORE INTO t_level_config (f_level, f_name, f_exp_threshold, f_icon ,f_description) VALUES (16, '金钱掌控者',     '12000', 'lvl-16','金钱为你工作');
INSERT OR IGNORE INTO t_level_config (f_level, f_name, f_exp_threshold, f_icon ,f_description) VALUES (17, '财务自由人',     '13600', 'lvl-17','被动收入覆盖支出');
INSERT OR IGNORE INTO t_level_config (f_level, f_name, f_exp_threshold, f_icon ,f_description) VALUES (18, '财富领航员',     '15300', 'lvl-18','引领他人走向财务健康');
INSERT OR IGNORE INTO t_level_config (f_level, f_name, f_exp_threshold, f_icon ,f_description) VALUES (19, '理财传奇',       '17100', 'lvl-19','经验丰富，值得信赖');
INSERT OR IGNORE INTO t_level_config (f_level, f_name, f_exp_threshold, f_icon ,f_description) VALUES (20, '财务之神',       '19000', 'lvl-20','登峰造极，掌控金钱');

-- =====================================================
-- 默认分类 seed（GAP-06）
-- init.sql 以 spring.sql.init.mode=always 每次启动执行，故必须幂等：
--   ① f_name 已按业务规则全局唯一（CategoryServiceImpl.savePreCheck），加唯一索引使 INSERT OR IGNORE 可去重；
--   ② 图标名取自前端 constants.ts CATEGORY_ICON_GROUPS，颜色取自 COLOR_PALETTE，同类型内一级分类颜色互不重复；
--   ③ 子分类默认继承父分类颜色（符合前端颜色占用约定），f_parent_id 用子查询按父名解析，故父分类须先插入。
-- =====================================================
CREATE UNIQUE INDEX IF NOT EXISTS idx_category_name ON t_category(f_name);

-- 支出一级分类
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('餐饮', NULL, 'food',      '#F56C6C', 'expense', 1);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('交通', NULL, 'transport', '#E6A23C', 'expense', 2);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('购物', NULL, 'shopping',  '#FA8C16', 'expense', 3);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('居住', NULL, 'home',      '#409EFF', 'expense', 4);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('娱乐', NULL, 'fun',       '#9B59B6', 'expense', 5);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('医疗', NULL, 'medical',   '#00B8A9', 'expense', 6);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('教育', NULL, 'edu',       '#67C23A', 'expense', 7);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('通讯', NULL, 'internet',  '#7B68EE', 'expense', 8);

-- 支出二级分类（继承父色）
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('早餐', (SELECT f_id FROM t_category WHERE f_name='餐饮'), 'rice',    '#F56C6C', 'expense', 1);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('午餐', (SELECT f_id FROM t_category WHERE f_name='餐饮'), 'food',    '#F56C6C', 'expense', 2);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('晚餐', (SELECT f_id FROM t_category WHERE f_name='餐饮'), 'hotpot',  '#F56C6C', 'expense', 3);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('外卖', (SELECT f_id FROM t_category WHERE f_name='餐饮'), 'takeout', '#F56C6C', 'expense', 4);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('水果', (SELECT f_id FROM t_category WHERE f_name='餐饮'), 'fruit',   '#F56C6C', 'expense', 5);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('饮品', (SELECT f_id FROM t_category WHERE f_name='餐饮'), 'coffee',  '#F56C6C', 'expense', 6);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('打车', (SELECT f_id FROM t_category WHERE f_name='交通'), 'taxi',     '#E6A23C', 'expense', 1);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('公交', (SELECT f_id FROM t_category WHERE f_name='交通'), 'bus',      '#E6A23C', 'expense', 2);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('地铁', (SELECT f_id FROM t_category WHERE f_name='交通'), 'subway',   '#E6A23C', 'expense', 3);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('加油', (SELECT f_id FROM t_category WHERE f_name='交通'), 'fuel',     '#E6A23C', 'expense', 4);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('停车', (SELECT f_id FROM t_category WHERE f_name='交通'), 'parking',  '#E6A23C', 'expense', 5);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('服饰', (SELECT f_id FROM t_category WHERE f_name='购物'), 'clothes',    '#FA8C16', 'expense', 1);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('数码', (SELECT f_id FROM t_category WHERE f_name='购物'), 'digital',    '#FA8C16', 'expense', 2);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('日用', (SELECT f_id FROM t_category WHERE f_name='购物'), 'daily',      '#FA8C16', 'expense', 3);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('超市', (SELECT f_id FROM t_category WHERE f_name='购物'), 'supermarket','#FA8C16', 'expense', 4);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('房租', (SELECT f_id FROM t_category WHERE f_name='居住'), 'rent',     '#409EFF', 'expense', 1);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('水电', (SELECT f_id FROM t_category WHERE f_name='居住'), 'electric', '#409EFF', 'expense', 2);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('燃气', (SELECT f_id FROM t_category WHERE f_name='居住'), 'gas',      '#409EFF', 'expense', 3);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('宽带', (SELECT f_id FROM t_category WHERE f_name='居住'), 'internet', '#409EFF', 'expense', 4);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('物业', (SELECT f_id FROM t_category WHERE f_name='居住'), 'property', '#409EFF', 'expense', 5);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('电影', (SELECT f_id FROM t_category WHERE f_name='娱乐'), 'movie',  '#9B59B6', 'expense', 1);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('运动', (SELECT f_id FROM t_category WHERE f_name='娱乐'), 'sport',  '#9B59B6', 'expense', 2);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('旅行', (SELECT f_id FROM t_category WHERE f_name='娱乐'), 'travel', '#9B59B6', 'expense', 3);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('音乐', (SELECT f_id FROM t_category WHERE f_name='娱乐'), 'music',  '#9B59B6', 'expense', 4);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('就诊', (SELECT f_id FROM t_category WHERE f_name='医疗'), 'hospital', '#00B8A9', 'expense', 1);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('药品', (SELECT f_id FROM t_category WHERE f_name='医疗'), 'medical',  '#00B8A9', 'expense', 2);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('体检', (SELECT f_id FROM t_category WHERE f_name='医疗'), 'checkup',  '#00B8A9', 'expense', 3);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('牙科', (SELECT f_id FROM t_category WHERE f_name='医疗'), 'dental',   '#00B8A9', 'expense', 4);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('课程', (SELECT f_id FROM t_category WHERE f_name='教育'), 'course', '#67C23A', 'expense', 1);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('书籍', (SELECT f_id FROM t_category WHERE f_name='教育'), 'book',   '#67C23A', 'expense', 2);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('培训', (SELECT f_id FROM t_category WHERE f_name='教育'), 'school', '#67C23A', 'expense', 3);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('话费', (SELECT f_id FROM t_category WHERE f_name='通讯'), 'membership', '#7B68EE', 'expense', 1);

-- 收入一级分类
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('工资', NULL, 'salary',   '#67C23A', 'income', 1);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('奖金', NULL, 'bonus',    '#F56C6C', 'income', 2);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('理财', NULL, 'invest',   '#409EFF', 'income', 3);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('兼职', NULL, 'parttime', '#E6A23C', 'income', 4);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('红包', NULL, 'redpacket','#E91E63', 'income', 5);

-- 收入二级分类（继承父色）
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('基本工资', (SELECT f_id FROM t_category WHERE f_name='工资'), 'salary',   '#67C23A', 'income', 1);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('绩效', (SELECT f_id FROM t_category WHERE f_name='工资'), 'bonus',    '#67C23A', 'income', 2);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('利息', (SELECT f_id FROM t_category WHERE f_name='理财'), 'interest', '#409EFF', 'income', 1);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('基金', (SELECT f_id FROM t_category WHERE f_name='理财'), 'invest',   '#409EFF', 'income', 2);

-- =====================================================
-- 扩展分类 seed（提升开箱即用体验）
-- 同样幂等（INSERT OR IGNORE + idx_category_name 全局唯一）；名称已逐一避让现有分类，
-- 如用「利息支出/车辆维修/房屋维修」而非「利息/维修」，防止全局唯一名冲突被静默忽略。
-- 新一级分类颜色取自高区分度色池（DISTINCT_COLORS），色相避开现有支出一级分类；子分类继承父色。
-- 图标名均取自前端 constants.ts CATEGORY_ICON_GROUPS。
-- =====================================================

-- 新增支出一级分类（sort 续 9~14）
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('人情',     NULL, 'gift',      '#D37295', 'expense', 9);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('宠物',     NULL, 'pet',       '#9C755F', 'expense', 10);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('美妆个护', NULL, 'cosmetics', '#FF9DA7', 'expense', 11);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('汽车',     NULL, 'maintenance','#B6992D', 'expense', 12);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('金融保险', NULL, 'insurance', '#499894', 'expense', 13);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('母婴亲子', NULL, 'baby',      '#EDC948', 'expense', 14);

-- 新增支出一级分类的子分类（继承父色）
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('随礼',     (SELECT f_id FROM t_category WHERE f_name='人情'), 'wedding',   '#D37295', 'expense', 1);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('礼物',     (SELECT f_id FROM t_category WHERE f_name='人情'), 'gift',      '#D37295', 'expense', 2);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('请客',     (SELECT f_id FROM t_category WHERE f_name='人情'), 'party',     '#D37295', 'expense', 3);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('孝敬父母', (SELECT f_id FROM t_category WHERE f_name='人情'), 'parents',   '#D37295', 'expense', 4);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('捐赠',     (SELECT f_id FROM t_category WHERE f_name='人情'), 'donate',    '#D37295', 'expense', 5);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('红包支出', (SELECT f_id FROM t_category WHERE f_name='人情'), 'redpacket', '#D37295', 'expense', 6);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('宠物食品', (SELECT f_id FROM t_category WHERE f_name='宠物'), 'food',      '#9C755F', 'expense', 1);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('宠物用品', (SELECT f_id FROM t_category WHERE f_name='宠物'), 'daily',     '#9C755F', 'expense', 2);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('宠物医疗', (SELECT f_id FROM t_category WHERE f_name='宠物'), 'medical',   '#9C755F', 'expense', 3);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('护肤',     (SELECT f_id FROM t_category WHERE f_name='美妆个护'), 'cosmetics', '#FF9DA7', 'expense', 1);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('彩妆',     (SELECT f_id FROM t_category WHERE f_name='美妆个护'), 'cosmetics', '#FF9DA7', 'expense', 2);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('个人护理', (SELECT f_id FROM t_category WHERE f_name='美妆个护'), 'daily',     '#FF9DA7', 'expense', 3);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('保养',     (SELECT f_id FROM t_category WHERE f_name='汽车'), 'maintenance', '#B6992D', 'expense', 1);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('车辆维修', (SELECT f_id FROM t_category WHERE f_name='汽车'), 'repair-home', '#B6992D', 'expense', 2);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('车险',     (SELECT f_id FROM t_category WHERE f_name='汽车'), 'insurance',   '#B6992D', 'expense', 3);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('洗车',     (SELECT f_id FROM t_category WHERE f_name='汽车'), 'clean',       '#B6992D', 'expense', 4);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('保险',     (SELECT f_id FROM t_category WHERE f_name='金融保险'), 'insurance',  '#499894', 'expense', 1);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('手续费',   (SELECT f_id FROM t_category WHERE f_name='金融保险'), 'loan',       '#499894', 'expense', 2);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('利息支出', (SELECT f_id FROM t_category WHERE f_name='金融保险'), 'interest',   '#499894', 'expense', 3);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('税费',     (SELECT f_id FROM t_category WHERE f_name='金融保险'), 'government', '#499894', 'expense', 4);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('奶粉',     (SELECT f_id FROM t_category WHERE f_name='母婴亲子'), 'milk',   '#EDC948', 'expense', 1);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('玩具',     (SELECT f_id FROM t_category WHERE f_name='母婴亲子'), 'toy',    '#EDC948', 'expense', 2);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('童装',     (SELECT f_id FROM t_category WHERE f_name='母婴亲子'), 'clothes','#EDC948', 'expense', 3);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('早教',     (SELECT f_id FROM t_category WHERE f_name='母婴亲子'), 'course', '#EDC948', 'expense', 4);

-- 补充现有支出一级分类的常用子分类（继承各自父色）
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('夜宵',   (SELECT f_id FROM t_category WHERE f_name='餐饮'), 'bbq',    '#F56C6C', 'expense', 7);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('甜点',   (SELECT f_id FROM t_category WHERE f_name='餐饮'), 'cake',   '#F56C6C', 'expense', 8);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('零食',   (SELECT f_id FROM t_category WHERE f_name='餐饮'), 'snack',  '#F56C6C', 'expense', 9);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('聚餐',   (SELECT f_id FROM t_category WHERE f_name='餐饮'), 'hotpot', '#F56C6C', 'expense', 10);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('酒水',   (SELECT f_id FROM t_category WHERE f_name='餐饮'), 'wine',   '#F56C6C', 'expense', 11);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('火车',   (SELECT f_id FROM t_category WHERE f_name='交通'), 'train',  '#E6A23C', 'expense', 6);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('飞机',   (SELECT f_id FROM t_category WHERE f_name='交通'), 'flight', '#E6A23C', 'expense', 7);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('高速费', (SELECT f_id FROM t_category WHERE f_name='交通'), 'toll',   '#E6A23C', 'expense', 8);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('共享单车',(SELECT f_id FROM t_category WHERE f_name='交通'), 'bike',   '#E6A23C', 'expense', 9);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('家电',   (SELECT f_id FROM t_category WHERE f_name='购物'), 'appliance', '#FA8C16', 'expense', 5);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('家居',   (SELECT f_id FROM t_category WHERE f_name='购物'), 'furniture', '#FA8C16', 'expense', 6);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('网购',   (SELECT f_id FROM t_category WHERE f_name='购物'), 'ecommerce', '#FA8C16', 'expense', 7);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('鞋包',   (SELECT f_id FROM t_category WHERE f_name='购物'), 'shoes',       '#FA8C16', 'expense', 8);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('文具',   (SELECT f_id FROM t_category WHERE f_name='购物'), 'stationery',  '#FA8C16', 'expense', 9);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('保洁',   (SELECT f_id FROM t_category WHERE f_name='居住'), 'clean',       '#409EFF', 'expense', 6);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('房屋维修',(SELECT f_id FROM t_category WHERE f_name='居住'), 'repair-home', '#409EFF', 'expense', 7);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('游戏',   (SELECT f_id FROM t_category WHERE f_name='娱乐'), 'fun',     '#9B59B6', 'expense', 5);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('KTV',    (SELECT f_id FROM t_category WHERE f_name='娱乐'), 'karaoke', '#9B59B6', 'expense', 6);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('聚会',   (SELECT f_id FROM t_category WHERE f_name='娱乐'), 'party',   '#9B59B6', 'expense', 7);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('摄影',   (SELECT f_id FROM t_category WHERE f_name='娱乐'), 'photo',   '#9B59B6', 'expense', 8);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('疫苗',   (SELECT f_id FROM t_category WHERE f_name='医疗'), 'vaccine', '#00B8A9', 'expense', 5);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('保健品', (SELECT f_id FROM t_category WHERE f_name='医疗'), 'medical', '#00B8A9', 'expense', 6);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('考试报名',(SELECT f_id FROM t_category WHERE f_name='教育'), 'school',  '#67C23A', 'expense', 4);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('会员订阅',(SELECT f_id FROM t_category WHERE f_name='通讯'), 'membership','#7B68EE','expense', 2);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('流量充值',(SELECT f_id FROM t_category WHERE f_name='通讯'), 'internet',  '#7B68EE','expense', 3);

-- 新增收入一级分类（sort 续 6~8；颜色避开现有收入一级分类色相）
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('报销',     NULL, 'refund',      '#B07AA1', 'income', 6);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('租金收入', NULL, 'rent-income', '#76B7B2', 'income', 7);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('中奖',     NULL, 'lottery',     '#EDC948', 'income', 8);

-- 新增/补充收入子分类（继承父色）
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('差旅报销', (SELECT f_id FROM t_category WHERE f_name='报销'), 'refund',  '#B07AA1', 'income', 1);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('医疗报销', (SELECT f_id FROM t_category WHERE f_name='报销'), 'medical', '#B07AA1', 'income', 2);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('私活',     (SELECT f_id FROM t_category WHERE f_name='兼职'), 'parttime',  '#E6A23C', 'income', 1);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('副业',     (SELECT f_id FROM t_category WHERE f_name='兼职'), 'parttime',  '#E6A23C', 'income', 2);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('微信红包', (SELECT f_id FROM t_category WHERE f_name='红包'), 'redpacket', '#E91E63', 'income', 1);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('节日红包', (SELECT f_id FROM t_category WHERE f_name='红包'), 'redpacket', '#E91E63', 'income', 2);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('股票',     (SELECT f_id FROM t_category WHERE f_name='理财'), 'invest',   '#409EFF', 'income', 3);
INSERT OR IGNORE INTO t_category (f_name, f_parent_id, f_icon, f_color, f_type, f_sort_order) VALUES ('分红',     (SELECT f_id FROM t_category WHERE f_name='理财'), 'interest', '#409EFF', 'income', 4);

-- =====================================================
-- 默认标签 seed（提升开箱体验）
-- t_tag.f_name 表级 UNIQUE，INSERT OR IGNORE 幂等；已存在的同名标签（如用户自建的「外卖」）保留其原色不被覆盖。
-- 标签是跨分类的标记维度，故取「报销/出差/刚需/冲动消费」等横切语义；颜色取自高区分度色池，逐个互不相同。
-- =====================================================
INSERT OR IGNORE INTO t_tag (f_name, f_color) VALUES ('报销',     '#E15759');
INSERT OR IGNORE INTO t_tag (f_name, f_color) VALUES ('出差',     '#4E79A7');
INSERT OR IGNORE INTO t_tag (f_name, f_color) VALUES ('旅游',     '#59A14F');
INSERT OR IGNORE INTO t_tag (f_name, f_color) VALUES ('网购',     '#F28E2B');
INSERT OR IGNORE INTO t_tag (f_name, f_color) VALUES ('聚餐',     '#B07AA1');
INSERT OR IGNORE INTO t_tag (f_name, f_color) VALUES ('送礼',     '#76B7B2');
INSERT OR IGNORE INTO t_tag (f_name, f_color) VALUES ('缴费',     '#EDC948');
INSERT OR IGNORE INTO t_tag (f_name, f_color) VALUES ('刚需',     '#9C755F');
INSERT OR IGNORE INTO t_tag (f_name, f_color) VALUES ('冲动消费', '#FF9DA7');
INSERT OR IGNORE INTO t_tag (f_name, f_color) VALUES ('优惠',     '#86BCB6');
INSERT OR IGNORE INTO t_tag (f_name, f_color) VALUES ('存钱',     '#8CD17D');
INSERT OR IGNORE INTO t_tag (f_name, f_color) VALUES ('分摊',     '#D37295');
INSERT OR IGNORE INTO t_tag (f_name, f_color) VALUES ('待还',     '#A0CBE8');
INSERT OR IGNORE INTO t_tag (f_name, f_color) VALUES ('学习',     '#F1CE63');
INSERT OR IGNORE INTO t_tag (f_name, f_color) VALUES ('健身',     '#B6992D');
INSERT OR IGNORE INTO t_tag (f_name, f_color) VALUES ('家庭',     '#499894');

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