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

INSERT IGNORE INTO `linux_cmd` (`cmd_name`, `cmd_category`, `cmd_summary`, `cmd_params`, `cmd_examples`, `cmd_remark`) VALUES
('tar', '压缩打包', '打包/解包归档文件，常配合 gzip 压缩。只打包不压缩叫归档，加 -z 才走 gzip。',
'-c  创建新的归档
-x  从归档中解出文件
-z  用 gzip 压缩/解压（.tar.gz）
-j  用 bzip2（.tar.bz2）
-v  显示过程
-f  指定归档文件名（必须紧跟文件名）
-C  解压到指定目录',
'tar -czvf backup.tar.gz /data/app => 打包压缩 /data/app 到 backup.tar.gz
tar -xzvf backup.tar.gz => 解压到当前目录
tar -xzvf backup.tar.gz -C /opt/app => 解压到指定目录
tar -tf backup.tar.gz => 只看归档里有什么，不解压',
'-f 后面必须紧跟文件名，所以 -czvf 这种连写顺序不能乱');

INSERT IGNORE INTO `linux_cmd` (`cmd_name`, `cmd_category`, `cmd_summary`, `cmd_params`, `cmd_examples`, `cmd_remark`) VALUES
('grep', '文本处理', '按关键字/正则过滤文本行，查日志最常用，常配合管道使用。',
'-i  忽略大小写
-n  显示行号
-r  递归搜目录
-c  只统计匹配行数
-v  反选（排除匹配行）
-A n / -B n  显示匹配行后/前 n 行',
'grep -n ''ERROR'' app.log => 找出含 ERROR 的行并显示行号
grep -c ''ERROR'' app.log => 统计 ERROR 出现的行数
tail -f app.log | grep --color ''Timeout'' => 实时滚动日志里高亮看 Timeout
grep -rn ''listen 80'' /etc/nginx => 递归搜目录，定位是哪段配置写了 listen 80',
'');

INSERT IGNORE INTO `linux_cmd` (`cmd_name`, `cmd_category`, `cmd_summary`, `cmd_params`, `cmd_examples`, `cmd_remark`) VALUES
('find', '文件与目录', '按名字/大小/时间在目录树里找文件，找到后还能用 -exec 顺手执行动作。',
'-name  按文件名匹配（通配符记得加引号）
-size  按大小（+100M = 大于 100MB）
-mtime n  n 天前修改过（+7 = 7 天以前）
-type f / d  只找文件 / 目录
-exec cmd {} \\;  对每个找到的文件执行 cmd',
'find /var/log -name ''*.log'' => 找出 /var/log 下所有 .log 文件
find / -size +500M -type f 2>/dev/null => 全盘找大于 500MB 的文件
find /data -mtime +7 -name ''*.tmp'' -delete => 删除 7 天前的临时文件',
'');

INSERT IGNORE INTO `linux_cmd` (`cmd_name`, `cmd_category`, `cmd_summary`, `cmd_params`, `cmd_examples`, `cmd_remark`) VALUES
('ps', '进程与系统', '快照式列出进程。排查进程在不在、启动参数是什么用 ps，动态看占用用 top。',
'-ef  全格式列出所有进程（SysV 风格）
aux  BSD 风格，含 CPU/内存占用
--sort=-%mem  按内存降序',
'ps -ef | grep java => 找 java 进程及启动参数
ps aux --sort=-%mem | head -10 => 内存占用最高的前 10 个进程',
'grep 过滤时总会多出一行 grep 自己，加 | grep -v grep 排除');

INSERT IGNORE INTO `linux_cmd` (`cmd_name`, `cmd_category`, `cmd_summary`, `cmd_params`, `cmd_examples`, `cmd_remark`) VALUES
('top', '进程与系统', '实时动态看 CPU/内存占用与进程排行。交互键：P 按 CPU 排、M 按内存排、q 退出。',
'-d n  刷新间隔秒数
-p pid  只盯指定进程',
'top => 交互式看整体负载（默认 3 秒刷新）
top -p 1234 => 只看 PID 1234 这一个进程
top -b -n 1 > top.txt => 批处理模式输出一次快照，适合脚本采集',
'');

INSERT IGNORE INTO `linux_cmd` (`cmd_name`, `cmd_category`, `cmd_summary`, `cmd_params`, `cmd_examples`, `cmd_remark`) VALUES
('free', '进程与系统', '看内存使用。重点看 available：真正还能分配给新程序的量。',
'-h  人性化单位
-m  以 MB 计',
'free -h => 内存与交换分区概览',
'buff/cache 是内核缓存不算被占用；别看 free 列吓自己，看 available');

INSERT IGNORE INTO `linux_cmd` (`cmd_name`, `cmd_category`, `cmd_summary`, `cmd_params`, `cmd_examples`, `cmd_remark`) VALUES
('ss', '网络', '看监听端口与连接状态。netstat 的现代替代品，输出更快更全。',
'-t  tcp
-u  udp
-l  只看监听（LISTEN）
-n  不做域名/服务名反解（快）
-p  显示占用端口的进程（需 root）',
'ss -tlnp => 本机监听了哪些 TCP 端口、被谁监听
ss -tnp | grep 6379 => 谁连着 6379',
'');

INSERT IGNORE INTO `linux_cmd` (`cmd_name`, `cmd_category`, `cmd_summary`, `cmd_params`, `cmd_examples`, `cmd_remark`) VALUES
('netstat', '网络', '老牌的网络连接查看工具，新系统建议用 ss，这里留给老发行版。',
'-tlnp  tcp + 监听 + 数字 + 进程
-rn  路由表',
'netstat -tlnp => 同 ss -tlnp，老系统用法
netstat -rn => 看路由表和默认网关',
'');

INSERT IGNORE INTO `linux_cmd` (`cmd_name`, `cmd_category`, `cmd_summary`, `cmd_params`, `cmd_examples`, `cmd_remark`) VALUES
('lsof', '进程与系统', '列出进程打开的文件。Linux 下一切皆文件，所以端口占用、被删但仍被写的日志都能查。',
'-i :port  查端口占用
-p pid  查某进程打开的文件
-u user  查某用户打开的文件',
'lsof -i :8080 => 谁占着 8080 端口
lsof -p 1234 => PID 1234 打开了哪些文件
lsof | grep deleted => 已删除但空间未释放的文件（磁盘满了先查这个）',
'');

INSERT IGNORE INTO `linux_cmd` (`cmd_name`, `cmd_category`, `cmd_summary`, `cmd_params`, `cmd_examples`, `cmd_remark`) VALUES
('df', '磁盘', '看文件系统整体磁盘占用。磁盘报警第一反应就是 df -h。',
'-h  人性化单位（K/M/G）
-i  看 inode 用量
-T  显示文件系统类型',
'df -h => 各挂载点磁盘使用率
df -i => 各挂载点 inode 使用率',
'磁盘没满但写不进文件？df -i 看 inode 是不是用光了');

INSERT IGNORE INTO `linux_cmd` (`cmd_name`, `cmd_category`, `cmd_summary`, `cmd_params`, `cmd_examples`, `cmd_remark`) VALUES
('du', '磁盘', '统计目录/文件实际占多大，找出谁把磁盘吃满就靠它。',
'-h  人性化单位
-s  只给总计，不逐个列子项
--max-depth=1  只展开一层子目录',
'du -sh /data => /data 一共多大
du -h --max-depth=1 /var | sort -rh | head -10 => /var 下哪个子目录最大（前 10）',
'du 是扫文件现统计，正在写的大文件和挂载点会让结果和 df 对不上，属正常');

INSERT IGNORE INTO `linux_cmd` (`cmd_name`, `cmd_category`, `cmd_summary`, `cmd_params`, `cmd_examples`, `cmd_remark`) VALUES
('tail', '文本处理', '看文件末尾。加 -f 变成追着滚动看，是盯日志的标准姿势。',
'-n n  看末尾 n 行
-f  持续跟踪新写入
-F  同 -f，且文件被轮换（改名重建）后自动跟上新文件',
'tail -f app.log => 实时滚动看日志
tail -n 200 app.log => 看最后 200 行
tail -F app.log | grep --color ERROR => 滚动日志里只看 ERROR',
'日志会 logrotate 轮换的场景用 -F，-f 会跟丢');

INSERT IGNORE INTO `linux_cmd` (`cmd_name`, `cmd_category`, `cmd_summary`, `cmd_params`, `cmd_examples`, `cmd_remark`) VALUES
('awk', '文本处理', '按列处理文本。取 ps/netstat 输出的某一列、做合计统计都靠它。',
'-F  指定分隔符
{print $n}  打印第 n 列（$0 整行，$NF 最后一列）',
'awk ''{print $1}'' access.log => 取访问日志第一列（客户端 IP）
ps aux | awk ''NR>1 {s+=$6} END {print s}'' => 汇总所有进程内存（KB）
awk -F: ''{print $1}'' /etc/passwd => 按冒号切列，取用户名',
'');

INSERT IGNORE INTO `linux_cmd` (`cmd_name`, `cmd_category`, `cmd_summary`, `cmd_params`, `cmd_examples`, `cmd_remark`) VALUES
('sed', '文本处理', '流编辑器，批量替换/删除文本行，改配置文件最常用的非交互手段。',
's/old/new/g  替换（g 全行替换）
-i  直接改文件（不加则只打印结果）
-n xp  只打印第 x 行',
'sed -i ''s/8080/9090/'' application.properties => 把文件里的 8080 改成 9090
sed -n ''100,120p'' app.log => 只看 100 到 120 行
sed -i ''/^$/d'' notes.txt => 删除空行',
'-i 直接改文件没有撤销，改之前先 cp 一份');

INSERT IGNORE INTO `linux_cmd` (`cmd_name`, `cmd_category`, `cmd_summary`, `cmd_params`, `cmd_examples`, `cmd_remark`) VALUES
('chmod', '权限', '改文件/目录的读写执行权限。数字 755 = rwxr-xr-x，目录必须有 x 才能进入。',
'-R  递归（对目录）
数字含义  4=r 2=w 1=x，三位分别对应属主/组/其他人',
'chmod +x deploy.sh => 给脚本加执行权限
chmod -R 755 /opt/app => 目录及内部全部 rwxr-xr-x
chmod 600 id_rsa => 私钥只给自己读写（ssh 强制要求）',
'');

INSERT IGNORE INTO `linux_cmd` (`cmd_name`, `cmd_category`, `cmd_summary`, `cmd_params`, `cmd_examples`, `cmd_remark`) VALUES
('chown', '权限', '改文件/目录的属主与属组，web 目录权限问题的另一半。',
'-R  递归
user:group  同时改属主和属组；只写 user 则只改属主',
'chown -R nginx:nginx /usr/share/nginx/html => 网站目录交给 nginx 用户
chown appuser /data/app/app.log => 只改属主',
'');

INSERT IGNORE INTO `linux_cmd` (`cmd_name`, `cmd_category`, `cmd_summary`, `cmd_params`, `cmd_examples`, `cmd_remark`) VALUES
('systemctl', '服务管理', 'systemd 的服务管理入口：启停、开机自启、看状态、看日志。',
'status/start/stop/restart  常规动作
enable/disable  设/取消开机自启
list-units  列出服务',
'systemctl status nginx => 看 nginx 状态和最近几条日志
systemctl enable --now docker => 设开机自启并立即启动（一步到位）
systemctl list-units --type=service --state=running => 正在运行的服务',
'restart 会断开连接重拉；只是改了配置想热加载，优先 reload');

INSERT IGNORE INTO `linux_cmd` (`cmd_name`, `cmd_category`, `cmd_summary`, `cmd_params`, `cmd_examples`, `cmd_remark`) VALUES
('journalctl', '服务管理', '看 systemd 服务的日志（systemctl status 里那几行不够看时用它）。',
'-u unit  只看某个服务
-f  持续跟踪
--since / --until  按时间过滤',
'journalctl -u nginx -f => 持续跟踪 nginx 日志
journalctl -u app --since ''2026-10-17 08:00'' => 看某服务 8 点以来的日志',
'');

INSERT IGNORE INTO `linux_cmd` (`cmd_name`, `cmd_category`, `cmd_summary`, `cmd_params`, `cmd_examples`, `cmd_remark`) VALUES
('curl', '网络', '命令行 HTTP 客户端。测接口连通性、带 header 调试、下载文件都用它。',
'-X  指定方法（GET/POST/...）
-H  加请求头
-d  请求体（加了默认变 POST）
-I  只看响应头
-o / -O  输出到文件',
'curl -I http://127.0.0.1:8080/actuator/health => 只看健康检查的响应头和状态码
curl -d ''a=1&b=2'' http://127.0.0.1:8080/api => POST 一段表单数据
curl -o app.jar http://repo.local/app.jar => 下载文件到本地',
'内网机器不通时先 curl -v 看卡在哪一步（DNS/连接/响应）');

INSERT IGNORE INTO `linux_cmd` (`cmd_name`, `cmd_category`, `cmd_summary`, `cmd_params`, `cmd_examples`, `cmd_remark`) VALUES
('scp', '文件传输', '基于 ssh 的远程复制，机器之间拷文件最省事的方式（配好密钥后无需密码）。',
'-r  递归拷目录
-P port  指定端口（大写 P，区别于 ssh 的小写 -p）',
'scp app.jar root@192.168.80.160:/opt/app/ => 本机文件推到远端
scp root@192.168.80.160:/var/log/app.log ./ => 远端文件拉回本机
scp -r dist root@192.168.80.160:/usr/share/nginx/ => 整个目录推上去',
'');

INSERT IGNORE INTO `linux_cmd` (`cmd_name`, `cmd_category`, `cmd_summary`, `cmd_params`, `cmd_examples`, `cmd_remark`) VALUES
('crontab', '定时任务', '管理用户的定时任务。时间格式：分 时 日 月 周 命令。',
'-l  列出当前任务
-e  编辑（保存即生效）',
'crontab -l => 看当前用户的定时任务
crontab -e => 编辑；加一行 0 2 * * * /opt/backup.sh 即每天凌晨 2 点执行',
'编辑保存即生效，不用重启任何服务；排查没执行先看 /var/log/cron');

INSERT IGNORE INTO `linux_cmd` (`cmd_name`, `cmd_category`, `cmd_summary`, `cmd_params`, `cmd_examples`, `cmd_remark`) VALUES
('history', '其他', '看当前用户执行过的命令。每条前面带序号，叹号序号可以直接重跑。',
'n  只显示最近 n 条
-c  清空历史',
'history | grep systemctl => 找之前敲过的 systemctl 命令
history 20 => 最近 20 条',
'多个终端共用一份历史，退出会话时才落盘');
