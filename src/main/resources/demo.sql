-- =====================================================================
-- lanyue 初始化脚本 —— SQLite 版
--
-- ⚠ 另有 MySQL 8 版：demo-mysql8.sql。两份文件**逐表对应、逐列对应，改一处必须改另一处**。
--   不能合并成一份，因为差异不是语法糖：
--     1) 标识符引号 —— SQLite 认 ANSI 双引号，MySQL 认反引号（MySQL 里双引号是字符串字面量）
--     2) MySQL 不允许主键建在 TEXT 上，所以两边统一用 VARCHAR(n)（见下）
--     3) 时间函数 —— datetime('now','localtime') vs NOW()、date('now','+90 day') vs DATE_ADD(...)
--     4) 幂等插入 —— INSERT OR IGNORE vs INSERT IGNORE
--     5) MySQL 建表要跟 ENGINE / CHARSET
--   除了这几类差异，剩下的语句应当能一眼对上，方便对照着改。
--
-- 为什么类型写成 VARCHAR(n) 而不是 SQLite 惯用的 TEXT(n)：
--   因为要跟 MySQL 对齐，而 MySQL 的 VARCHAR 必须给长度。SQLite 这边完全接受这种写法——
--   列类型名只决定**亲和性**（VARCHAR -> TEXT 亲和性），长度**不做校验**，
--   所以 VARCHAR(50) 在 SQLite 里就是一个不受长度约束的文本列，行为跟 TEXT(50) 一样。
--   顺带一提 SQLite 的 TEXT(50) 里的 50 本来也是被忽略的，写它只是给人看的。
--
-- 表结构（列名/类型/长度）必须与 DbInitializer.TABLES 里的表定义保持一致，
-- 改一处要改两处——启动时的自动建表兜底（CREATE TABLE IF NOT EXISTS / 缺列补列）
-- 就是从那里生成 DDL 的。业务表不执行本脚本也会由 DbInitializer 自动建出，
-- 本脚本的价值在于 DROP 重建 + 演示数据。
-- =====================================================================

-- ----------------------------
-- Table structure for users（系统用户，登录认证与权限判断都取自这张表）
--   id_user      登录名，主键，创建后不允许修改
--   name_user    姓名，仅用于展示
--   password     密码摘要。存量数据是 SM3（大写十六进制），登录一次后由
--                Spring Security 自动升级成 BCrypt（{bcrypt}$2a$...）并写回。
--                所以这里是 100 而不是 64 —— BCrypt 密文 60 字符，加上 {bcrypt} 前缀 8 字符。
--   create_time  创建时间，文本列，格式 yyyy-MM-dd HH:mm:ss
--   email        邮箱
--   organization 所属组织
--   cd_phone     手机号
--   expire_time  有效期至，文本列；为空表示长期有效，当天 23:59:59 之前仍可登录。
--                纯日期（date('now')，如 2026-12-20）与完整时间戳
--                （yyyy-MM-dd HH:mm:ss）两种写法都能读：读取走 TextDateTypeHandler
--                的宽容解析，写入一律用 yyyy-MM-dd HH:mm:ss
--   user_flag    '1' 或空 = 启用，'0' = 禁用（禁用后无法登录）
--   permission   逗号分隔的权限串：ADD 新增 / DELETE 删除 / UPDATE 修改 /
--                QUERY 查询 / UPLOAD 上传，ALL 表示全部权限
--
-- 为什么时间列是文本而不是"日期类型"：
--   因为同一份实体要同时跑 SQLite 和 MySQL，而 SQLite 根本没有原生日期类型。
--   格式固定 yyyy-MM-dd HH:mm:ss 且零填充，文本的字典序就等于时间序，
--   所以 BETWEEN / ORDER BY / 索引范围扫描都正常工作，功能上没有损失。
--   换成 MySQL 的 DATETIME 反而要写两套 ResultMap。
--
-- 这一段是可重复执行的：建表用 IF NOT EXISTS，插入用 INSERT OR IGNORE，
-- 因此不会把已有账号冲掉。内置管理员 admin 另有兜底——DbInitializer 在每次
-- 启动时都会检查它是否存在且可用（见 src/main/java/com/sl/config/DbInitializer.java），
-- 即便下面这行 INSERT 被 IGNORE 跳过，也不会出现"谁都登不进去"的情况。
-- ----------------------------
CREATE TABLE IF NOT EXISTS "users" (
                         "id_user" VARCHAR(50) NOT NULL,
                         "name_user" VARCHAR(50),
                         "password" VARCHAR(100),
                         "create_time" VARCHAR(32),
                         "email" VARCHAR(64),
                         "organization" VARCHAR(64),
                         "cd_phone" VARCHAR(32),
                         "expire_time" VARCHAR(32),
                         "user_flag" VARCHAR(1),
                         "permission" VARCHAR(255),
                         PRIMARY KEY ("id_user")
);

INSERT OR IGNORE INTO "users" VALUES ('admin', '系统管理员', 'DC1FD00E3EEEB940FF46F457BF97D66BA7FCC36E0B20802383DE142860E76AE6', datetime('now','localtime'), 'admin@example.com', '运维部', '13800000000', NULL, '1', 'ALL');
INSERT OR IGNORE INTO "users" VALUES ('test', '测试账号', '55E12E91650D2FEC56EC74E1D3E4DDBFCE2EF3A65890C2A19ECF88A307E76A23', datetime('now','localtime'), 'test@example.com', '测试组', '13900000001', date('now','+90 day'), '1', 'ADD,');
INSERT OR IGNORE INTO "users" VALUES ('developer', '开发账号', '82BE8D25346156C92E408CF511675D9075EEB955D9C5FD9A57505D9CDFBBAAC9', datetime('now','localtime'), 'dev@example.com', '研发部', '13900000002', date('now','+30 day'), '1', 'ADD,');
-- 下面两条是演示数据，用来看"已禁用""已过期""7 天内到期"这几种状态在页面上的样子，不需要可以直接在用户管理页删掉
INSERT OR IGNORE INTO "users" VALUES ('guest', '访客演示', 'DC1FD00E3EEEB940FF46F457BF97D66BA7FCC36E0B20802383DE142860E76AE6', datetime('now','localtime'), 'guest@example.com', '外部合作方', '13900000003', NULL, '0', 'QUERY,');
INSERT OR IGNORE INTO "users" VALUES ('temp', '临时账号', '55E12E91650D2FEC56EC74E1D3E4DDBFCE2EF3A65890C2A19ECF88A307E76A23', datetime('now','-60 day'), 'temp@example.com', '外包团队', '13900000004', date('now','-5 day'), '1', 'ADD,QUERY,');


DROP TABLE IF EXISTS "common_project_mgmt";
CREATE TABLE "common_project_mgmt" (
                                       "id_host" VARCHAR(64) NOT NULL,
                                       "id_project" VARCHAR(64) NOT NULL,
                                       "name_project" VARCHAR(64) NOT NULL,
                                       "cd_path" VARCHAR(255) NOT NULL,
                                       "cmd_start" VARCHAR(255),
                                       "cmd_stop" VARCHAR(255),
                                       "cmd_restart" VARCHAR(255),
                                       "cmd_refresh" VARCHAR(255),
                                       "cmd_status" VARCHAR(255),
                                       "cmd_status_success_key" VARCHAR(128),
                                       "cd_description" VARCHAR(255),
                                       "cd_tag" VARCHAR(64),
                                       PRIMARY KEY ("id_host", "id_project")
);
INSERT INTO "common_project_mgmt" ("id_host", "id_project", "name_project", "cd_path", "cmd_start", "cmd_stop", "cmd_restart", "cmd_refresh", "cmd_status", "cmd_status_success_key", "cd_description", "cd_tag") VALUES ('192.168.190.160', 'nginx', 'nginx', '/usr/local/nginx/sbin', './nginx ', './nginx -s stop', '', './nginx -s reload', 'ps -ef | grep nginx', 'nginx: master', 'nginx配置示例', '');

-- ----------------------------
-- Table structure for connection_info（远程主机连接信息）
-- 注意描述列叫 cd_desc，**不叫 desc**：
--   DESC 是 MySQL 8 的保留字（官方关键字表里标着 DESC (R)），叫 desc 的话在 MySQL 上
--   CREATE TABLE 都过不去，SELECT desc FROM ... 也会语法报错。改名后顺带和同族的
--   cd_password / cd_key_path / cd_logpath 对齐。
--   已有的旧库不用手工改：DbInitializer 启动时会幂等执行一次列改名。
-- ----------------------------
DROP TABLE IF EXISTS "connection_info";
CREATE TABLE "connection_info" (
                                   "id_host" VARCHAR(64) NOT NULL,
                                   "cd_port" VARCHAR(10) NOT NULL,
                                   "id_user" VARCHAR(64) NOT NULL,
                                   "cd_password" VARCHAR(255),
                                   "cd_key_path" VARCHAR(255),
                                   "cd_logpath" VARCHAR(255),
                                   "cd_desc" VARCHAR(255),
                                   "cd_group" VARCHAR(64),
                                   PRIMARY KEY ("id_host", "cd_port", "id_user")
);

-- ----------------------------
-- Table structure for server_group（免登录服务器列表的分组定义）
-- 只有分组名一列；机器归属在 connection_info.cd_group 上。
-- 「默认分组」是虚拟的，不写进这张表。
-- ----------------------------
DROP TABLE IF EXISTS "server_group";
CREATE TABLE "server_group" (
                                "group_name" VARCHAR(64) NOT NULL,
                                PRIMARY KEY ("group_name")
);

INSERT INTO "server_group" VALUES ('生产环境');
INSERT INTO "server_group" VALUES ('测试环境');

-- ----------------------------
-- Records of connection_info
-- ----------------------------
INSERT INTO "connection_info" VALUES ('192.168.190.100', '22', 'root', 'test', NULL, NULL,'测试', '默认分组');

-- ----------------------------
-- Table structure for log_path（远程主机上的日志目录）
-- ----------------------------
DROP TABLE IF EXISTS "log_path";
CREATE TABLE "log_path" (
                            "id_loghost" VARCHAR(64) NOT NULL,
                            "id_log_path" VARCHAR(255) NOT NULL,
                            "name_log" VARCHAR(64),
                            PRIMARY KEY ("id_loghost", "id_log_path")
);

DROP TABLE IF EXISTS "projects";
CREATE TABLE "projects" (
                            "id_host" VARCHAR(64) NOT NULL,
                            "id_project" VARCHAR(64) NOT NULL,
                            "name_project" VARCHAR(64),
                            "cd_parent_path" VARCHAR(255) NOT NULL,
                            "cd_tag" VARCHAR(64),
                            "cd_command" VARCHAR(255),
                            "jvm_param" VARCHAR(255),
                            "jar_param" VARCHAR(128),
                            "jar_name" VARCHAR(255),
                            "cd_description" VARCHAR(255),
                            PRIMARY KEY ("id_host", "id_project")
);


-- ----------------------------
-- Table structure for tomcat_info（Tomcat 实例信息）
-- ----------------------------
DROP TABLE IF EXISTS "tomcat_info";
CREATE TABLE "tomcat_info" (
                               "id_host" VARCHAR(64) NOT NULL,
                               "tomcat_id" VARCHAR(50) NOT NULL,
                               "name_tomcat" VARCHAR(64),
                               "tomcat_path" VARCHAR(255),
                               "webapp_path" VARCHAR(255),
                               "tag" VARCHAR(64),
                               "cd_description" VARCHAR(255),
                               PRIMARY KEY ("id_host", "tomcat_id")
);

-- ----------------------------
-- Table structure for script_mgmt（脚本管理，其他工具 → 脚本管理）
--   id_script      脚本 ID，主键，取创建时刻的纳秒值（System.nanoTime()），应用生成
--   script_name    脚本名称（卡片标题 / 搜索关键字）
--   script_desc    脚本说明
--   script_content 脚本内容（shell 全文）。6000 × 4 字节 ≈ 24KB（utf8mb4），与
--                  DbInitializer.SCRIPT_MGMT 的长度定义保持一致
--   create_time    创建时间，文本列 yyyy-MM-dd HH:mm:ss
-- ----------------------------
DROP TABLE IF EXISTS "script_mgmt";
CREATE TABLE "script_mgmt" (
                               "id_script" VARCHAR(32) NOT NULL,
                               "script_name" VARCHAR(128) NOT NULL,
                               "script_desc" VARCHAR(255),
                               "script_content" VARCHAR(6000),
                               "create_time" VARCHAR(32),
                               PRIMARY KEY ("id_script")
);

-- ----------------------------
-- Table structure for app_setting（界面偏好 / 零散状态的通用 kv 表）
--   setting_key    键，由调用方约定，例如 compose.pref.admin；主键
--   setting_value  值，纯文本；compose 偏好是"lastHost=..." + 每行一条历史根目录
--   update_time    最后写入时间，文本列 yyyy-MM-dd HH:mm:ss
--
-- 长度取 191：191 × 4 字节 = 764，是 utf8mb4 下唯一索引的经典安全上限。
-- SQLite 不看长度，这只是为了跟 MySQL 版保持一致。
--
-- 注意：这张表**不需要手工执行本脚本**。AppSettingStore 在容器启动时
-- （@PostConstruct）会自己 CREATE TABLE IF NOT EXISTS 建出来，写在这里只是
-- 让 demo.db 的表结构文档保持完整。这一段不 DROP，重复执行也不会影响已有偏好。
-- ----------------------------
CREATE TABLE IF NOT EXISTS "app_setting" (
                               "setting_key" VARCHAR(191) NOT NULL,
                               "setting_value" TEXT,
                               "update_time" VARCHAR(32),
                               PRIMARY KEY ("setting_key")
);
