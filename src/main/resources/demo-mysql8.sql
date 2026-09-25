-- =====================================================================
-- lanyue 初始化脚本 —— MySQL 8 版
--
-- 与 demo.sql（SQLite 版）**逐表对应，改一处必须改另一处**。
-- 两份文件不能合并成一份，因为差异不是"语法糖"级别的：
--
--   1) 标识符引号：SQLite 认 ANSI 双引号，MySQL 认反引号
--      （MySQL 里双引号默认是字符串字面量，除非 sql_mode 开了 ANSI_QUOTES）
--   2) 文本列类型：**必须用 VARCHAR(n)，不能用 TEXT**
--      MySQL 的 PRIMARY KEY 不允许建在 TEXT / BLOB 上（没有前缀长度就无法参与排序比较），
--      而本脚本里所有主键都是字符串列。SQLite 那边 VARCHAR(n) 等价于 TEXT（只看亲和性、
--      不校验长度），所以应用代码里生成 DDL 时两边共用同一个 type 表达式。
--   3) 时间函数：SQLite 的 datetime('now','localtime') / date('now','+90 day')
--      换成 MySQL 的 NOW() / DATE_ADD(CURDATE(), INTERVAL 90 DAY)
--   4) 幂等插入：INSERT OR IGNORE -> INSERT IGNORE
--   5) 长度：MySQL 的 VARCHAR 必须给长度，SQLite 的 TEXT(n) 里那个 n 其实是被忽略的，
--      所以这里有若干长度是"新补出来"的，取值留了余量。
--
-- 另一个关键差异：connection_info 的描述列。
-- SQLite 版叫 "desc"，但 DESC 是 MySQL 8 的保留字（官方关键字表里标着 DESC (R)），
-- 这列在 MySQL 上不引号直接语法报错、引号又会让 MyBatis 的结果集映射对不上列标签。
-- 所以两边统一改名为 cd_desc，顺带和同族的 cd_password / cd_key_path / cd_logpath 对齐。
-- 已有的旧 SQLite 库不用手工改：DbInitializer 启动时会幂等地做一次
-- ALTER TABLE ... RENAME COLUMN（见 com.sl.config.DbInitializer#applyCompatMigrations）。
--
-- 执行方式：
--   mysql -u lanyue -p lanyue < demo-mysql8.sql
-- 前置条件：库已建且字符集为 utf8mb4
--   CREATE DATABASE lanyue DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
-- =====================================================================

-- ----------------------------
-- Table structure for users（系统用户，登录认证与权限判断都取自这张表）
--   id_user      登录名，主键，创建后不允许修改
--   name_user    姓名，仅用于展示
--   password     密码摘要。存量数据是 SM3（大写十六进制），登录一次后由
--                Spring Security 自动升级成 BCrypt（{bcrypt}$2a$...）并写回。
--                所以这里是 100 而不是 64 —— BCrypt 密文 60 字符，加上 {bcrypt} 前缀 8 字符。
--   create_time  创建时间，字符串列，格式 yyyy-MM-dd HH:mm:ss
--   expire_time  有效期至，字符串列；为空表示长期有效，当天 23:59:59 之前仍可登录。
--                纯日期（2026-12-20）与完整时间戳两种写法都能读：读取走
--                TextDateTypeHandler 的宽容解析，写入一律用 yyyy-MM-dd HH:mm:ss。
--   user_flag    '1' 或空 = 启用，'0' = 禁用
--   permission   逗号分隔的权限串：ADD / DELETE / UPDATE / QUERY / UPLOAD，ALL 表示全部
--
-- 为什么时间列是字符串而不是 DATETIME：
--   因为同一份实体要同时跑 SQLite 和 MySQL，SQLite 没有原生日期类型。
--   格式固定为 yyyy-MM-dd HH:mm:ss 且零填充，字符串的字典序就等于时间序，
--   所以 BETWEEN / ORDER BY / 索引范围扫描全都正常工作，功能上没有损失。
--   换成 DATETIME 反而要写两套 ResultMap。
-- ----------------------------
CREATE TABLE IF NOT EXISTS `users` (
    `id_user`     VARCHAR(50)  NOT NULL,
    `name_user`   VARCHAR(50),
    `password`    VARCHAR(100),
    `create_time` VARCHAR(32),
    `email`       VARCHAR(64),
    `organization` VARCHAR(64),
    `cd_phone`    VARCHAR(32),
    `expire_time` VARCHAR(32),
    `user_flag`   VARCHAR(1),
    `permission`  VARCHAR(255),
    PRIMARY KEY (`id_user`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 这一段是可重复执行的（INSERT IGNORE），不会把已有账号冲掉。
-- 内置管理员 admin 另有兜底：DbInitializer 每次启动都会检查它是否存在且可用，
-- 即便下面这行被 IGNORE 跳过，也不会出现"谁都登不进去"的情况。
-- 表结构必须与 DbInitializer.TABLES 里的表定义保持一致，改一处要改两处。
-- 业务表不执行本脚本也会由 DbInitializer 自动建出（CREATE TABLE IF NOT EXISTS），
-- 本脚本的价值在于 DROP 重建 + 演示数据。
INSERT IGNORE INTO `users`
    (`id_user`, `name_user`, `password`, `create_time`, `email`, `organization`, `cd_phone`, `expire_time`, `user_flag`, `permission`)
VALUES
    ('admin', '系统管理员', 'DC1FD00E3EEEB940FF46F457BF97D66BA7FCC36E0B20802383DE142860E76AE6', NOW(), 'admin@example.com', '运维部', '13800000000', NULL, '1', 'ALL'),
    ('test', '测试账号', '55E12E91650D2FEC56EC74E1D3E4DDBFCE2EF3A65890C2A19ECF88A307E76A23', NOW(), 'test@example.com', '测试组', '13900000001', DATE_ADD(CURDATE(), INTERVAL 90 DAY), '1', 'ADD,'),
    ('developer', '开发账号', '82BE8D25346156C92E408CF511675D9075EEB955D9C5FD9A57505D9CDFBBAAC9', NOW(), 'dev@example.com', '研发部', '13900000002', DATE_ADD(CURDATE(), INTERVAL 30 DAY), '1', 'ADD,'),
    -- 下面两条是演示数据，用来看"已禁用""已过期"这几种状态在页面上的样子，不需要可以直接删
    ('guest', '访客演示', 'DC1FD00E3EEEB940FF46F457BF97D66BA7FCC36E0B20802383DE142860E76AE6', NOW(), 'guest@example.com', '外部合作方', '13900000003', NULL, '0', 'QUERY,'),
    ('temp', '临时账号', '55E12E91650D2FEC56EC74E1D3E4DDBFCE2EF3A65890C2A19ECF88A307E76A23', DATE_SUB(NOW(), INTERVAL 60 DAY), 'temp@example.com', '外包团队', '13900000004', DATE_SUB(CURDATE(), INTERVAL 5 DAY), '1', 'ADD,QUERY,');

-- ----------------------------
-- Table structure for common_project_mgmt（通用项目：靠一串 shell 命令起停的项目）
-- 主键是 (id_host, id_project)，两列都必须有长度。
-- ----------------------------
DROP TABLE IF EXISTS `common_project_mgmt`;
CREATE TABLE `common_project_mgmt` (
    `id_host`               VARCHAR(64)  NOT NULL,
    `id_project`            VARCHAR(64)  NOT NULL,
    `name_project`          VARCHAR(64)  NOT NULL,
    `cd_path`               VARCHAR(255) NOT NULL,
    `cmd_start`             VARCHAR(255),
    `cmd_stop`              VARCHAR(255),
    `cmd_restart`           VARCHAR(255),
    `cmd_refresh`           VARCHAR(255),
    `cmd_status`            VARCHAR(255),
    `cmd_status_success_key` VARCHAR(128),
    `cd_description`        VARCHAR(255),
    `cd_tag`                VARCHAR(64),
    PRIMARY KEY (`id_host`, `id_project`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO `common_project_mgmt`
    (`id_host`, `id_project`, `name_project`, `cd_path`, `cmd_start`, `cmd_stop`, `cmd_restart`, `cmd_refresh`, `cmd_status`, `cmd_status_success_key`, `cd_description`, `cd_tag`)
VALUES
    ('192.168.190.160', 'nginx', 'nginx', '/usr/local/nginx/sbin', './nginx ', './nginx -s stop', '', './nginx -s reload', 'ps -ef | grep nginx', 'nginx: master', 'nginx配置示例', '');

-- ----------------------------
-- Table structure for connection_info（远程主机连接信息）
-- 注意描述列叫 cd_desc，不叫 desc —— DESC 是 MySQL 8 保留字。理由见文件头。
-- ----------------------------
DROP TABLE IF EXISTS `connection_info`;
CREATE TABLE `connection_info` (
    `id_host`     VARCHAR(64)  NOT NULL,
    `cd_port`     VARCHAR(10)  NOT NULL,
    `id_user`     VARCHAR(64)  NOT NULL,
    `cd_password` VARCHAR(255),
    `cd_key_path` VARCHAR(255),
    `cd_logpath`  VARCHAR(255),
    `cd_desc`     VARCHAR(255),
    `cd_group`    VARCHAR(64),
    PRIMARY KEY (`id_host`, `cd_port`, `id_user`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ----------------------------
-- Table structure for server_group（免登录服务器列表的分组定义）
-- 只有分组名一列；机器归属在 connection_info.cd_group 上。
-- 「默认分组」是虚拟的，不写进这张表。
-- ----------------------------
DROP TABLE IF EXISTS `server_group`;
CREATE TABLE `server_group` (
    `group_name`  VARCHAR(64)  NOT NULL,
    PRIMARY KEY (`group_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO `server_group` (`group_name`) VALUES ('生产环境'), ('测试环境');

-- ----------------------------
-- Records of connection_info
-- ----------------------------
INSERT INTO `connection_info`
    (`id_host`, `cd_port`, `id_user`, `cd_password`, `cd_key_path`, `cd_logpath`, `cd_desc`, `cd_group`)
VALUES
    ('192.168.190.100', '22', 'root', 'test', NULL, NULL, '测试', '默认分组');

-- ----------------------------
-- Table structure for log_path（远程主机上的日志目录）
-- ----------------------------
DROP TABLE IF EXISTS `log_path`;
CREATE TABLE `log_path` (
    `id_loghost`  VARCHAR(64)  NOT NULL,
    `id_log_path` VARCHAR(255) NOT NULL,
    `name_log`    VARCHAR(64),
    PRIMARY KEY (`id_loghost`, `id_log_path`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ----------------------------
-- Table structure for projects（jar 类项目的部署信息）
-- ----------------------------
DROP TABLE IF EXISTS `projects`;
CREATE TABLE `projects` (
    `id_host`        VARCHAR(64)  NOT NULL,
    `id_project`     VARCHAR(64)  NOT NULL,
    `name_project`   VARCHAR(64),
    `cd_parent_path` VARCHAR(255) NOT NULL,
    `cd_tag`         VARCHAR(64),
    `cd_command`     VARCHAR(255),
    `jvm_param`      VARCHAR(255),
    `jar_param`      VARCHAR(128),
    `jar_name`       VARCHAR(255),
    `cd_description` VARCHAR(255),
    PRIMARY KEY (`id_host`, `id_project`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ----------------------------
-- Table structure for tomcat_info（Tomcat 实例信息）
-- ----------------------------
DROP TABLE IF EXISTS `tomcat_info`;
CREATE TABLE `tomcat_info` (
    `id_host`        VARCHAR(64)  NOT NULL,
    `tomcat_id`      VARCHAR(50)  NOT NULL,
    `name_tomcat`    VARCHAR(64),
    `tomcat_path`    VARCHAR(255),
    `webapp_path`    VARCHAR(255),
    `tag`            VARCHAR(64),
    `cd_description` VARCHAR(255),
    PRIMARY KEY (`id_host`, `tomcat_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ----------------------------
-- Table structure for script_mgmt（脚本管理，其他工具 → 脚本管理）
--   id_script      脚本 ID，主键，取创建时刻的纳秒值（System.nanoTime()），应用生成
--   script_name    脚本名称（卡片标题 / 搜索关键字）
--   script_desc    脚本说明
--   script_content 脚本内容（shell 全文）。6000 × 4 字节 ≈ 24KB（utf8mb4），
--                  与 DbInitializer.SCRIPT_MGMT 的长度定义保持一致
--   create_time    创建时间，字符串列 yyyy-MM-dd HH:mm:ss
-- ----------------------------
DROP TABLE IF EXISTS `script_mgmt`;
CREATE TABLE `script_mgmt` (
    `id_script`      VARCHAR(32)  NOT NULL,
    `script_name`    VARCHAR(128) NOT NULL,
    `script_desc`    VARCHAR(255),
    `script_content` VARCHAR(6000),
    `create_time`    VARCHAR(32),
    PRIMARY KEY (`id_script`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ----------------------------
-- Table structure for app_setting（界面偏好 / 零散状态的通用 kv 表）
--   setting_key    键，由调用方约定，例如 compose.pref.admin；主键
--   setting_value  值，纯文本
--   update_time    最后写入时间，字符串列 yyyy-MM-dd HH:mm:ss
--
-- 长度取 191 而不是 255：191 × 4 字节 = 764，是 utf8mb4 下唯一索引的经典安全上限，
-- 老版本 InnoDB 的 767 字节限制就是这么绕过去的。MySQL 8 的 DYNAMIC 行格式其实放宽到
-- 3072 字节了，这里保持 191 是为了将来万一要用 utf8mb3 或降级部署时不用改表。
--
-- 这张表**不需要手工执行本脚本**：AppSettingStore 在容器启动时（@PostConstruct）
-- 会自己 CREATE TABLE IF NOT EXISTS 建出来，写在这里只是让表结构文档保持完整。
-- 这一段不 DROP，重复执行也不会影响已有偏好。
-- ----------------------------
CREATE TABLE IF NOT EXISTS `app_setting` (
    `setting_key`   VARCHAR(191) NOT NULL,
    `setting_value` TEXT,
    `update_time`   VARCHAR(32),
    PRIMARY KEY (`setting_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
