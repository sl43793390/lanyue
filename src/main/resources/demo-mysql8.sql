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
-- Table structure for drawio_file（drawio绘图，其他工具 → drawio绘图）
--   id_file       图表 ID，主键，取创建时刻的纳秒值（System.nanoTime()），应用生成
--   id_user       归属用户，users.id_user（登录名），用户隔离的依据
--   file_name     图表名称
--   file_content  图表内容，draw.io 的 mxfile XML（纯文本）。16000 × 4 字节 ≈ 64KB
--                 （utf8mb4），贴近 MySQL 8 行大小上限（65535 字节），与
--                 DbInitializer.DRAWIO_FILE 的长度定义保持一致
--   create_time   创建时间，字符串列 yyyy-MM-dd HH:mm:ss
--   update_time   最后保存时间，字符串列 yyyy-MM-dd HH:mm:ss
--
-- 注意：这张表不需要手工执行本脚本，DbInitializer 启动时会自动建出来，
-- 写在这里只是让表结构文档保持完整。
-- ----------------------------
CREATE TABLE IF NOT EXISTS `drawio_file` (
    `id_file`      VARCHAR(32)  NOT NULL,
    `id_user`      VARCHAR(50)  NOT NULL,
    `file_name`    VARCHAR(128) NOT NULL,
    `file_content` MEDIUMTEXT,
    `create_time`  VARCHAR(32),
    `update_time`  VARCHAR(32),
    PRIMARY KEY (`id_file`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ----------------------------
-- Table structure for compose_project（Docker-Compose 项目登记，Docker-Compose 管理页）
--   平台上创建过的每个 compose 项目记一行，列表页数据源就是这张表（不再按根目录扫描），
--   运行状态（是否在跑 / 容器数）每次点「刷新」时现查目标机。
--   id_host       目标主机标识，格式 host:port
--   name          compose 项目名（-p 的值，容器名/卷名前缀），同一台主机上唯一
--   cd_directory  项目目录（目标机绝对路径）
--   cd_files      参与叠加的 compose 文件名，逗号分隔，顺序即 -f 顺序
--   id_user       创建人（users.id_user）；表格按"所有用户创建过的"展示，不按人过滤
--   create_time   创建时间，字符串列 yyyy-MM-dd HH:mm:ss
--
-- 注意：这张表不需要手工执行本脚本，DbInitializer 启动时会自动建出来，
-- 写在这里只是让表结构文档保持完整。
-- ----------------------------
DROP TABLE IF EXISTS `compose_project`;
CREATE TABLE `compose_project` (
    `id_host`       VARCHAR(64)  NOT NULL,
    `name`          VARCHAR(64)  NOT NULL,
    `cd_directory`  VARCHAR(255),
    `cd_files`      VARCHAR(255),
    `cd_description` VARCHAR(255),
    `id_user`       VARCHAR(64),
    `create_time`   VARCHAR(32),
    PRIMARY KEY (`id_host`, `name`)
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

-- ----------------------------
-- Table structure for linux_cmd（Linux 命令查询，Linux工具 → Linux命令查询）
--   cmd_name      命令名，主键（命令名天然唯一，不另造 ID）
--   cmd_category  分类（压缩打包 / 文本处理 / 网络等），列表分组排序用
--   cmd_summary   功能说明（一句话）
--   cmd_params    参数说明，一行一个参数：参数 + 两个空格 + 说明
--   cmd_examples  使用示例，一行一条：命令 => 说明
--                 （=> 是命令与说明的分隔符；竖线 | 在 shell 命令里太常见不能用。
--                  第一条示例会作为详情弹窗输入框的默认命令）
--   cmd_remark    备注（坑点），可为空
--
-- 表结构与 DbInitializer.LINUX_CMD 保持一致，改一处要改两处。
-- 不执行本脚本时 DbInitializer 启动会自动建表（只建结构不灌数据），
-- 这一段用 INSERT IGNORE 灌种子命令，可重复执行。
-- 全量命令数据（152 条，Rocky Linux 8.6 基准）在项目根目录 linux_commands.csv；
-- MySQL 8 导入：LOAD DATA LOCAL INFILE 'linux_commands.csv' INTO TABLE linux_cmd
--   FIELDS TERMINATED BY ',' OPTIONALLY ENCLOSED BY '"'
--   LINES TERMINATED BY '\r\n' IGNORE 1 LINES;（按实际换行符调整 \r\n / \n）
-- ----------------------------
DROP TABLE IF EXISTS `linux_cmd`;
CREATE TABLE `linux_cmd` (
                             `cmd_name` VARCHAR(64) NOT NULL,
                             `cmd_category` VARCHAR(64),
                             `cmd_summary` VARCHAR(2000) NOT NULL,
                             `cmd_params` VARCHAR(3000),
                             `cmd_examples` VARCHAR(4000),
                             `cmd_remark` VARCHAR(1000),
                             PRIMARY KEY (`cmd_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ----------------------------
-- Table structure for github_favorite（github优选，其他工具 → github优选）
--   id_github     项目 ID，主键，取创建时刻的纳秒值（System.nanoTime()），应用生成
--   project_name  项目名称（卡片标题）
--   project_url   GitHub 项目地址，https://github.com/组织/仓库
--   project_desc  项目介绍（卡片中部，超出三行折叠）
--   cd_category   分类，为空表示未分类（页面归到「未分类」组，排在最后）。
--                 分类不单独建表：收藏夹量级的数据，分类信息就存在每一行上，
--                 页面临时聚合；「分类管理」弹窗可重命名 / 删除分类
--   create_time   创建时间，字符串列 yyyy-MM-dd HH:mm:ss
--
-- 表结构与 DbInitializer.GITHUB_FAVORITE 保持一致，改一处要改两处。
-- 下面 12 条是演示数据（INSERT IGNORE，可重复执行），页面上按分类分组展示。
-- ----------------------------
DROP TABLE IF EXISTS `github_favorite`;
CREATE TABLE `github_favorite` (
    `id_github`     VARCHAR(32)  NOT NULL,
    `project_name`  VARCHAR(128) NOT NULL,
    `project_url`   VARCHAR(500) NOT NULL,
    `project_desc`  VARCHAR(1000),
    `cd_category`   VARCHAR(64),
    `create_time`   VARCHAR(32),
    PRIMARY KEY (`id_github`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO "main"."github_favorite" ("id_github", "project_name", "project_url", "project_desc", "cd_category", "create_time") VALUES ('2000000000000000001', 'nginx', 'https://github.com/nginx/nginx', '高性能 HTTP 与反向代理服务器，也可用作邮件代理、TCP/UDP 代理，互联网基础设施里的常青树。', 'DevOps 与监控', '2026-10-18 10:41:04');
INSERT INTO "main"."github_favorite" ("id_github", "project_name", "project_url", "project_desc", "cd_category", "create_time") VALUES ('2000000000000000002', 'Jenkins', 'https://github.com/jenkinsci/jenkins', '开源持续集成与持续交付平台，插件生态最丰富的 CI/CD 工具，Pipeline 即代码。', 'DevOps 与监控', '2026-10-18 10:41:04');
INSERT INTO "main"."github_favorite" ("id_github", "project_name", "project_url", "project_desc", "cd_category", "create_time") VALUES ('2000000000000000003', 'Prometheus', 'https://github.com/prometheus/prometheus', '云原生监控与告警系统，拉取式指标采集，PromQL 查询语言，Kubernetes 生态标配。', 'DevOps 与监控', '2026-10-18 10:41:04');
INSERT INTO "main"."github_favorite" ("id_github", "project_name", "project_url", "project_desc", "cd_category", "create_time") VALUES ('2000000000000000004', 'Grafana', 'https://github.com/grafana/grafana', '指标可视化与仪表盘平台，支持 Prometheus、MySQL、Elasticsearch 等上百种数据源。', 'DevOps 与监控', '2026-10-18 10:41:04');
INSERT INTO "main"."github_favorite" ("id_github", "project_name", "project_url", "project_desc", "cd_category", "create_time") VALUES ('2000000000000000005', 'Redis', 'https://github.com/redis/redis', '内存键值数据库，支持字符串、哈希、列表、集合等结构，最常用的缓存与轻量消息中间件。', '数据库与中间件', '2026-10-18 10:41:04');
INSERT INTO "main"."github_favorite" ("id_github", "project_name", "project_url", "project_desc", "cd_category", "create_time") VALUES ('2000000000000000006', 'MySQL Server', 'https://github.com/mysql/mysql-server', 'MySQL 服务端源码，全球使用最广泛的开源关系型数据库。', '数据库与中间件', '2026-10-18 10:41:04');
INSERT INTO "main"."github_favorite" ("id_github", "project_name", "project_url", "project_desc", "cd_category", "create_time") VALUES ('2000000000000000007', 'Elasticsearch', 'https://github.com/elastic/elasticsearch', '分布式搜索与分析引擎，日志检索与全文搜索场景的事实标准。', '数据库与中间件', '2026-10-18 10:41:04');
INSERT INTO "main"."github_favorite" ("id_github", "project_name", "project_url", "project_desc", "cd_category", "create_time") VALUES ('2000000000000000008', 'Spring Boot', 'https://github.com/spring-projects/spring-boot', 'Java 生态最流行的应用框架，约定优于配置，开箱即用搭建生产级应用。', '开发框架与工具', '2026-10-18 10:41:04');
INSERT INTO "main"."github_favorite" ("id_github", "project_name", "project_url", "project_desc", "cd_category", "create_time") VALUES ('2000000000000000009', 'MyBatis-Plus', 'https://github.com/baomidou/mybatis-plus', 'MyBatis 的增强工具，内置通用 CRUD、分页、条件构造器，少写 SQL 也能干活。', '开发框架与工具', '2026-10-18 10:41:04');
INSERT INTO "main"."github_favorite" ("id_github", "project_name", "project_url", "project_desc", "cd_category", "create_time") VALUES ('2000000000000000010', 'Hutool', 'https://github.com/dromara/hutool', '国产 Java 工具集，覆盖字符串、日期、加解密、HTTP、Excel 等日常开发场景。', '开发框架与工具', '2026-10-18 10:41:04');
INSERT INTO "main"."github_favorite" ("id_github", "project_name", "project_url", "project_desc", "cd_category", "create_time") VALUES ('2000000000000000011', 'Vaadin Flow', 'https://github.com/vaadin/flow', '纯 Java 构建 Web 前端的全栈框架，服务端驱动 UI，本平台的技术底座。', '开发框架与工具', '2026-10-18 10:41:04');
INSERT INTO "main"."github_favorite" ("id_github", "project_name", "project_url", "project_desc", "cd_category", "create_time") VALUES ('2000000000000000012', 'Docker Compose', 'https://github.com/docker/compose', '多容器 Docker 应用的定义与运行工具，一个 YAML 文件起一套服务。', 'DevOps 与监控', '2026-10-18 10:41:04');
INSERT INTO "main"."github_favorite" ("id_github", "project_name", "project_url", "project_desc", "cd_category", "create_time") VALUES ('192089610940500', 'hertzbeat', 'https://github.com/apache/hertzbeat', 'AI 驱动的下一代开源实时观测系统。指标日志统一收集，告警一站分发，智能管控分析。无需 Agent，高性能集群，提供强大的自定义监控和状态页构建能力。', 'DevOps 与监控', '2026-10-18 13:34:18');
INSERT INTO "main"."github_favorite" ("id_github", "project_name", "project_url", "project_desc", "cd_category", "create_time") VALUES ('192177004202900', 'WindTerm', 'https://github.com/kingToolbox/WindTerm', 'A professional cross-platform SSH/Sftp/Shell/Telnet/Tmux/Serial terminal.', 'Linux 工具', '2026-10-18 13:35:45');
INSERT INTO "main"."github_favorite" ("id_github", "project_name", "project_url", "project_desc", "cd_category", "create_time") VALUES ('193780705554300', 'kiss-the-pdf', 'https://github.com/positiveabhii/kiss-the-pdf', 'A free, open-source, privacy-first alternative to iLovePDF.', 'PDF处理工具', '2026-10-18 14:02:29');
INSERT INTO "main"."github_favorite" ("id_github", "project_name", "project_url", "project_desc", "cd_category", "create_time") VALUES ('193967255113400', 'frp', 'https://github.com/fatedier/frp', 'A fast reverse proxy to help you expose a local server behind a NAT or firewall to the internet.', 'Linux 工具', '2026-10-18 14:05:35');
INSERT INTO "main"."github_favorite" ("id_github", "project_name", "project_url", "project_desc", "cd_category", "create_time") VALUES ('194469724776100', 'kkFileView', 'https://github.com/kekingcn/kkFileView', 'Universal File Online Preview Project based on Spring-Boot', '文档预览', '2026-10-18 14:13:58');
INSERT INTO "main"."github_favorite" ("id_github", "project_name", "project_url", "project_desc", "cd_category", "create_time") VALUES ('194566276773700', 'Stirling-PDF', 'https://github.com/Stirling-Tools/Stirling-PDF', '1 PDF Application on GitHub that lets you edit PDFs on any device anywhere', 'PDF处理工具', '2026-10-18 14:15:34');
INSERT INTO "main"."github_favorite" ("id_github", "project_name", "project_url", "project_desc", "cd_category", "create_time") VALUES ('196159573893500', 'pdfcraft', 'https://github.com/PDFCraftTool/pdfcraft', 'PDFCraft is a free, privacy-focused PDF toolkit that runs entirely in your browser. With 90+ professional tools, you can edit, convert, merge, split, and secure your PDF files without ever uploading them to a server.', 'PDF处理工具', '2026-10-18 14:42:08');
