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
-- Table structure for drawio_file（drawio绘图，其他工具 → drawio绘图）
--   id_file       图表 ID，主键，取创建时刻的纳秒值（System.nanoTime()），应用生成
--   id_user       归属用户，users.id_user（登录名），用户隔离的依据
--   file_name     图表名称
--   file_content  图表内容，draw.io 的 mxfile XML（纯文本）。16000 × 4 字节 ≈ 64KB
--                 （utf8mb4），贴近 MySQL 8 行大小上限，与 DbInitializer.DRAWIO_FILE
--                 的长度定义保持一致；普通流程图 XML 在几 KB 量级
--   create_time   创建时间，文本列 yyyy-MM-dd HH:mm:ss
--   update_time   最后保存时间，文本列 yyyy-MM-dd HH:mm:ss
--
-- 注意：这张表不需要手工执行本脚本，DbInitializer 启动时会自动建出来，
-- 写在这里只是让 demo.db 的表结构文档保持完整。
-- ----------------------------
CREATE TABLE IF NOT EXISTS "drawio_file" (
                     "id_file" VARCHAR(32) NOT NULL,
                    "id_user" VARCHAR(50) NOT NULL,
                    "file_name" VARCHAR(128) NOT NULL,
                    "file_content" TEXT,
                    "create_time" VARCHAR(32),
                    "update_time" VARCHAR(32),
                    PRIMARY KEY ("id_file")
    );

-- ----------------------------
-- Table structure for compose_project（Docker-Compose 项目登记，Docker-Compose 管理页）
--   平台上创建过的每个 compose 项目记一行，列表页数据源就是这张表（不再按根目录扫描），
--   运行状态（是否在跑 / 容器数）每次点「刷新」时现查目标机。
--   id_host       目标主机标识，格式 host:port
--   name          compose 项目名（-p 的值，容器名/卷名前缀），同一台主机上唯一
--   cd_directory  项目目录（目标机绝对路径）
--   cd_files      参与叠加的 compose 文件名，逗号分隔，顺序即 -f 顺序
--   id_user       创建人（users.id_user）；表格按"所有用户创建过的"展示，不按人过滤
--   create_time   创建时间，文本列 yyyy-MM-dd HH:mm:ss
--
-- 注意：这张表不需要手工执行本脚本，DbInitializer 启动时会自动建出来，
-- 写在这里只是让 demo.db 的表结构文档保持完整。
-- ----------------------------
DROP TABLE IF EXISTS "compose_project";
CREATE TABLE "compose_project" (
                                    "id_host" VARCHAR(64) NOT NULL,
                                    "name" VARCHAR(64) NOT NULL,
                                    "cd_directory" VARCHAR(255),
                                    "cd_files" VARCHAR(255),
                                    "cd_description" VARCHAR(255),
                                    "id_user" VARCHAR(64),
                                    "create_time" VARCHAR(32),
                                    PRIMARY KEY ("id_host", "name")
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
-- 这一段用 INSERT OR IGNORE 灌种子命令，可重复执行。
-- 全量命令数据（152 条，Rocky Linux 8.6 基准）在项目根目录 linux_commands.csv，
-- 用 scripts/import-linux-cmd.py 导入；本段种子只是最小演示集。
-- ----------------------------
DROP TABLE IF EXISTS "linux_cmd";
CREATE TABLE "linux_cmd" (
                             "cmd_name" VARCHAR(64) NOT NULL,
                             "cmd_category" VARCHAR(64),
                             "cmd_summary" VARCHAR(2000) NOT NULL,
                             "cmd_params" VARCHAR(3000),
                             "cmd_examples" VARCHAR(4000),
                             "cmd_remark" VARCHAR(1000),
                             PRIMARY KEY ("cmd_name")
);

INSERT OR IGNORE INTO "linux_cmd" ("cmd_name", "cmd_category", "cmd_summary", "cmd_params", "cmd_examples", "cmd_remark") VALUES
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

INSERT OR IGNORE INTO "linux_cmd" ("cmd_name", "cmd_category", "cmd_summary", "cmd_params", "cmd_examples", "cmd_remark") VALUES
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

INSERT OR IGNORE INTO "linux_cmd" ("cmd_name", "cmd_category", "cmd_summary", "cmd_params", "cmd_examples", "cmd_remark") VALUES
('find', '文件与目录', '按名字/大小/时间在目录树里找文件，找到后还能用 -exec 顺手执行动作。',
'-name  按文件名匹配（通配符记得加引号）
-size  按大小（+100M = 大于 100MB）
-mtime n  n 天前修改过（+7 = 7 天以前）
-type f / d  只找文件 / 目录
-exec cmd {} \;  对每个找到的文件执行 cmd',
'find /var/log -name ''*.log'' => 找出 /var/log 下所有 .log 文件
find / -size +500M -type f 2>/dev/null => 全盘找大于 500MB 的文件
find /data -mtime +7 -name ''*.tmp'' -delete => 删除 7 天前的临时文件',
'');

INSERT OR IGNORE INTO "linux_cmd" ("cmd_name", "cmd_category", "cmd_summary", "cmd_params", "cmd_examples", "cmd_remark") VALUES
('ps', '进程与系统', '快照式列出进程。排查进程在不在、启动参数是什么用 ps，动态看占用用 top。',
'-ef  全格式列出所有进程（SysV 风格）
aux  BSD 风格，含 CPU/内存占用
--sort=-%mem  按内存降序',
'ps -ef | grep java => 找 java 进程及启动参数
ps aux --sort=-%mem | head -10 => 内存占用最高的前 10 个进程',
'grep 过滤时总会多出一行 grep 自己，加 | grep -v grep 排除');

INSERT OR IGNORE INTO "linux_cmd" ("cmd_name", "cmd_category", "cmd_summary", "cmd_params", "cmd_examples", "cmd_remark") VALUES
('top', '进程与系统', '实时动态看 CPU/内存占用与进程排行。交互键：P 按 CPU 排、M 按内存排、q 退出。',
'-d n  刷新间隔秒数
-p pid  只盯指定进程',
'top => 交互式看整体负载（默认 3 秒刷新）
top -p 1234 => 只看 PID 1234 这一个进程
top -b -n 1 > top.txt => 批处理模式输出一次快照，适合脚本采集',
'');

INSERT OR IGNORE INTO "linux_cmd" ("cmd_name", "cmd_category", "cmd_summary", "cmd_params", "cmd_examples", "cmd_remark") VALUES
('free', '进程与系统', '看内存使用。重点看 available：真正还能分配给新程序的量。',
'-h  人性化单位
-m  以 MB 计',
'free -h => 内存与交换分区概览',
'buff/cache 是内核缓存不算被占用；别看 free 列吓自己，看 available');

INSERT OR IGNORE INTO "linux_cmd" ("cmd_name", "cmd_category", "cmd_summary", "cmd_params", "cmd_examples", "cmd_remark") VALUES
('ss', '网络', '看监听端口与连接状态。netstat 的现代替代品，输出更快更全。',
'-t  tcp
-u  udp
-l  只看监听（LISTEN）
-n  不做域名/服务名反解（快）
-p  显示占用端口的进程（需 root）',
'ss -tlnp => 本机监听了哪些 TCP 端口、被谁监听
ss -tnp | grep 6379 => 谁连着 6379',
'');

INSERT OR IGNORE INTO "linux_cmd" ("cmd_name", "cmd_category", "cmd_summary", "cmd_params", "cmd_examples", "cmd_remark") VALUES
('netstat', '网络', '老牌的网络连接查看工具，新系统建议用 ss，这里留给老发行版。',
'-tlnp  tcp + 监听 + 数字 + 进程
-rn  路由表',
'netstat -tlnp => 同 ss -tlnp，老系统用法
netstat -rn => 看路由表和默认网关',
'');

INSERT OR IGNORE INTO "linux_cmd" ("cmd_name", "cmd_category", "cmd_summary", "cmd_params", "cmd_examples", "cmd_remark") VALUES
('lsof', '进程与系统', '列出进程打开的文件。Linux 下一切皆文件，所以端口占用、被删但仍被写的日志都能查。',
'-i :port  查端口占用
-p pid  查某进程打开的文件
-u user  查某用户打开的文件',
'lsof -i :8080 => 谁占着 8080 端口
lsof -p 1234 => PID 1234 打开了哪些文件
lsof | grep deleted => 已删除但空间未释放的文件（磁盘满了先查这个）',
'');

INSERT OR IGNORE INTO "linux_cmd" ("cmd_name", "cmd_category", "cmd_summary", "cmd_params", "cmd_examples", "cmd_remark") VALUES
('df', '磁盘', '看文件系统整体磁盘占用。磁盘报警第一反应就是 df -h。',
'-h  人性化单位（K/M/G）
-i  看 inode 用量
-T  显示文件系统类型',
'df -h => 各挂载点磁盘使用率
df -i => 各挂载点 inode 使用率',
'磁盘没满但写不进文件？df -i 看 inode 是不是用光了');

INSERT OR IGNORE INTO "linux_cmd" ("cmd_name", "cmd_category", "cmd_summary", "cmd_params", "cmd_examples", "cmd_remark") VALUES
('du', '磁盘', '统计目录/文件实际占多大，找出谁把磁盘吃满就靠它。',
'-h  人性化单位
-s  只给总计，不逐个列子项
--max-depth=1  只展开一层子目录',
'du -sh /data => /data 一共多大
du -h --max-depth=1 /var | sort -rh | head -10 => /var 下哪个子目录最大（前 10）',
'du 是扫文件现统计，正在写的大文件和挂载点会让结果和 df 对不上，属正常');

INSERT OR IGNORE INTO "linux_cmd" ("cmd_name", "cmd_category", "cmd_summary", "cmd_params", "cmd_examples", "cmd_remark") VALUES
('tail', '文本处理', '看文件末尾。加 -f 变成追着滚动看，是盯日志的标准姿势。',
'-n n  看末尾 n 行
-f  持续跟踪新写入
-F  同 -f，且文件被轮换（改名重建）后自动跟上新文件',
'tail -f app.log => 实时滚动看日志
tail -n 200 app.log => 看最后 200 行
tail -F app.log | grep --color ERROR => 滚动日志里只看 ERROR',
'日志会 logrotate 轮换的场景用 -F，-f 会跟丢');

INSERT OR IGNORE INTO "linux_cmd" ("cmd_name", "cmd_category", "cmd_summary", "cmd_params", "cmd_examples", "cmd_remark") VALUES
('awk', '文本处理', '按列处理文本。取 ps/netstat 输出的某一列、做合计统计都靠它。',
'-F  指定分隔符
{print $n}  打印第 n 列（$0 整行，$NF 最后一列）',
'awk ''{print $1}'' access.log => 取访问日志第一列（客户端 IP）
ps aux | awk ''NR>1 {s+=$6} END {print s}'' => 汇总所有进程内存（KB）
awk -F: ''{print $1}'' /etc/passwd => 按冒号切列，取用户名',
'');

INSERT OR IGNORE INTO "linux_cmd" ("cmd_name", "cmd_category", "cmd_summary", "cmd_params", "cmd_examples", "cmd_remark") VALUES
('sed', '文本处理', '流编辑器，批量替换/删除文本行，改配置文件最常用的非交互手段。',
's/old/new/g  替换（g 全行替换）
-i  直接改文件（不加则只打印结果）
-n xp  只打印第 x 行',
'sed -i ''s/8080/9090/'' application.properties => 把文件里的 8080 改成 9090
sed -n ''100,120p'' app.log => 只看 100 到 120 行
sed -i ''/^$/d'' notes.txt => 删除空行',
'-i 直接改文件没有撤销，改之前先 cp 一份');

INSERT OR IGNORE INTO "linux_cmd" ("cmd_name", "cmd_category", "cmd_summary", "cmd_params", "cmd_examples", "cmd_remark") VALUES
('chmod', '权限', '改文件/目录的读写执行权限。数字 755 = rwxr-xr-x，目录必须有 x 才能进入。',
'-R  递归（对目录）
数字含义  4=r 2=w 1=x，三位分别对应属主/组/其他人',
'chmod +x deploy.sh => 给脚本加执行权限
chmod -R 755 /opt/app => 目录及内部全部 rwxr-xr-x
chmod 600 id_rsa => 私钥只给自己读写（ssh 强制要求）',
'');

INSERT OR IGNORE INTO "linux_cmd" ("cmd_name", "cmd_category", "cmd_summary", "cmd_params", "cmd_examples", "cmd_remark") VALUES
('chown', '权限', '改文件/目录的属主与属组，web 目录权限问题的另一半。',
'-R  递归
user:group  同时改属主和属组；只写 user 则只改属主',
'chown -R nginx:nginx /usr/share/nginx/html => 网站目录交给 nginx 用户
chown appuser /data/app/app.log => 只改属主',
'');

INSERT OR IGNORE INTO "linux_cmd" ("cmd_name", "cmd_category", "cmd_summary", "cmd_params", "cmd_examples", "cmd_remark") VALUES
('systemctl', '服务管理', 'systemd 的服务管理入口：启停、开机自启、看状态、看日志。',
'status/start/stop/restart  常规动作
enable/disable  设/取消开机自启
list-units  列出服务',
'systemctl status nginx => 看 nginx 状态和最近几条日志
systemctl enable --now docker => 设开机自启并立即启动（一步到位）
systemctl list-units --type=service --state=running => 正在运行的服务',
'restart 会断开连接重拉；只是改了配置想热加载，优先 reload');

INSERT OR IGNORE INTO "linux_cmd" ("cmd_name", "cmd_category", "cmd_summary", "cmd_params", "cmd_examples", "cmd_remark") VALUES
('journalctl', '服务管理', '看 systemd 服务的日志（systemctl status 里那几行不够看时用它）。',
'-u unit  只看某个服务
-f  持续跟踪
--since / --until  按时间过滤',
'journalctl -u nginx -f => 持续跟踪 nginx 日志
journalctl -u app --since ''2026-10-17 08:00'' => 看某服务 8 点以来的日志',
'');

INSERT OR IGNORE INTO "linux_cmd" ("cmd_name", "cmd_category", "cmd_summary", "cmd_params", "cmd_examples", "cmd_remark") VALUES
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

INSERT OR IGNORE INTO "linux_cmd" ("cmd_name", "cmd_category", "cmd_summary", "cmd_params", "cmd_examples", "cmd_remark") VALUES
('scp', '文件传输', '基于 ssh 的远程复制，机器之间拷文件最省事的方式（配好密钥后无需密码）。',
'-r  递归拷目录
-P port  指定端口（大写 P，区别于 ssh 的小写 -p）',
'scp app.jar root@192.168.80.160:/opt/app/ => 本机文件推到远端
scp root@192.168.80.160:/var/log/app.log ./ => 远端文件拉回本机
scp -r dist root@192.168.80.160:/usr/share/nginx/ => 整个目录推上去',
'');

INSERT OR IGNORE INTO "linux_cmd" ("cmd_name", "cmd_category", "cmd_summary", "cmd_params", "cmd_examples", "cmd_remark") VALUES
('crontab', '定时任务', '管理用户的定时任务。时间格式：分 时 日 月 周 命令。',
'-l  列出当前任务
-e  编辑（保存即生效）',
'crontab -l => 看当前用户的定时任务
crontab -e => 编辑；加一行 0 2 * * * /opt/backup.sh 即每天凌晨 2 点执行',
'编辑保存即生效，不用重启任何服务；排查没执行先看 /var/log/cron');

INSERT OR IGNORE INTO "linux_cmd" ("cmd_name", "cmd_category", "cmd_summary", "cmd_params", "cmd_examples", "cmd_remark") VALUES
('history', '其他', '看当前用户执行过的命令。每条前面带序号，叹号序号可以直接重跑。',
'n  只显示最近 n 条
-c  清空历史',
'history | grep systemctl => 找之前敲过的 systemctl 命令
history 20 => 最近 20 条',
'多个终端共用一份历史，退出会话时才落盘');

-- ----------------------------
-- Table structure for github_favorite（github优选，其他工具 → github优选）
--   id_github     项目 ID，主键，取创建时刻的纳秒值（System.nanoTime()），应用生成
--   project_name  项目名称（卡片标题）
--   project_url   GitHub 项目地址，https://github.com/组织/仓库
--   project_desc  项目介绍（卡片中部，超出三行折叠）
--   cd_category   分类，为空表示未分类（页面归到「未分类」组，排在最后）。
--                 分类不单独建表：收藏夹量级的数据，分类信息就存在每一行上，
--                 页面临时聚合；「分类管理」弹窗可重命名 / 删除分类
--   create_time   创建时间，文本列 yyyy-MM-dd HH:mm:ss
--
-- 表结构与 DbInitializer.GITHUB_FAVORITE 保持一致，改一处要改两处。
-- 下面 12 条是演示数据（INSERT OR IGNORE，可重复执行），页面上按分类分组展示。
-- ----------------------------
DROP TABLE IF EXISTS "github_favorite";
CREATE TABLE "github_favorite" (
                               "id_github" VARCHAR(32) NOT NULL,
                               "project_name" VARCHAR(128) NOT NULL,
                               "project_url" VARCHAR(500) NOT NULL,
                               "project_desc" VARCHAR(1000),
                               "cd_category" VARCHAR(64),
                               "create_time" VARCHAR(32),
                               PRIMARY KEY ("id_github")
);

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
