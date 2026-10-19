# 揽月运维管理平台（lanyue）

[English](README-en.md) | 中文

一套面向中小团队的自助运维平台：Web 页面上完成服务器管理、日志搜索、文件管理、应用发布、Docker / Docker-Compose 管理和 SSH 终端操作，不必再挨个登录机器敲命令。

基于 **Spring Boot 3.5 + Vaadin 24** 构建，单 jar 部署，同时兼容 **SQLite**（开箱即用）和 **MySQL 8** 两种数据库。

![免登录服务器列表](img/服务器管理11.png)

---

## 目录

- [功能总览](#功能总览)
- [系统架构](#系统架构)
- [快速开始](#快速开始)
- [配置说明](#配置说明)
- [默认账号](#默认账号)
- [目录结构](#目录结构)
- [常见问题](#常见问题)

---

## 功能总览

### 概览

- **使用手册**：内置操作说明，首次登录默认打开。

### 本地应用管理

- **本地日志搜索**：搜索本机日志文件，支持按关键字、时间范围过滤，详情页高亮展示。
- **本地文件管理**：本机文件浏览与编辑，纯文本文件（扩展名白名单内）直接在浏览器里改，配 CodeMirror 编辑器，按文件类型自动切换高亮模式（yaml / nginx / shell / properties 等）。
- **jar 项目管理**：登记本机 jar 应用，一键启动 / 停止 / 重启，跟踪运行状态。
- **Tomcat 管理**：Tomcat 实例的部署与管理。
- **通用项目管理**：以"主机 + 项目"为维度登记任意应用，自定义启停命令和状态检测关键字（如 nginx）。

### 远程应用管理

**免登录服务器列表** —— 集中管理所有远程主机（SSH 账号密码 / 密钥），按分组组织。每台机器提供六个行内入口：

| 入口 | 说明 |
| --- | --- |
| 容器和镜像管理 | 远程 Docker 容器 / 镜像的查看、启动、停止、删除 |
| Compose 管理 | docker-compose 项目的启停、配置编辑、新建项目 |
| 应用管理 | 按预设命令执行应用的启动 / 停止 / 重启 / 状态检查 |
| SSH 终端 | 浏览器内原生终端（xterm.js + WebSocket），支持字号、主题切换 |
| 文件管理 | SFTP 文件浏览，上传、下载、重命名、删除、在线编辑，支持跨机器文件分发 |
| 指标监控 | CPU / 内存 / 磁盘采样，进程 TOP 10，5 秒自动刷新 |

**远程日志搜索** —— SSH 到远程机器 grep 日志文件，在线查看匹配详情。

### Docker 管理

- **容器和镜像管理**：容器生命周期操作、日志查看、进入终端；镜像列表与清理。
- **Docker-Compose 管理**：按登记管理远程主机上的 compose 项目，支持项目新建（多文件 YAML 编辑器）、启动 / 重启 / 停止、配置 diff、历史目录切换。

### drawio 绘图

内嵌 draw.io 编辑器 + 按用户隔离的图表库：图表下拉直接切换，编辑器内保存即落库，支持另存、删除。

### Linux 工具

- **常用 Linux 命令**：把日常要 ssh 上去敲的事收成按钮——防火墙开关与放行端口、用户与 sudo 授权、服务启停、系统巡检（内存 / 磁盘 / 进程 / 端口 / SELinux 状态）、crontab 在线编辑、主机名修改。高危动作刻意不提供。
- **Linux 命令查询**：离线命令手册，内置 152 条常用命令（分类 / 功能说明 / 参数 / 可复制示例 / 备注），首次部署时自动导入，无需手工初始化。
- **脚本管理**：卡片式 shell 脚本库，集中保存、搜索、复制、下发执行。
- **自定义脚本监控**：选一台服务器 + 一个监控脚本（内置编辑器可直接改），按配置间隔循环执行，结果解析成指标表格，保留最近 10 轮。

### 其他工具

| 工具 | 说明 |
| --- | --- |
| JSON 美化 | 粘贴即自动美化，CodeMirror 编辑器 |
| Base64 编解码 | 文字编解码、图片转 Base64（可选 Data URI 前缀） |
| 加密工具 | 常用摘要与对称加密，含国密 SM3 / SM4（BouncyCastle） |
| 二维码生成 | 输出格式 / 纠错级别 / 尺寸可调，支持下载 |
| 正则表达式测试 | 常用表达式模板、匹配着色、捕获分组、批量替换（支持 `$1` 引用）、速查表 |
| Cron 表达式生成器 | 三种主流格式页签（Linux crontab / Quartz / Spring），逐字段可视化配置 |
| 图片压缩 | 多选 / 拖拽上传，选格式调质量，逐张展示节省比例 |
| 图片转换 | HEIC / JPG / PNG / WebP / GIF / BMP / TIFF / AVIF / ICO 互转，纯浏览器端完成 |
| 文本对比 | 双栏 diff，差异直接以红 / 绿标记在输入框行上 |

### github 优选

卡片式开源项目收藏夹：项目名称、介绍、一键跳转 GitHub，支持分类与编辑。

### 用户与权限

- 基于 Spring Security 的登录认证，密码使用 BCrypt 存储（存量 SM3 摘要在登录时自动渐进升级）；连续 5 次密码错误冻结账号 10 分钟。
- **RBAC 角色管理**：角色是权限的集合，权限树按"根 = 菜单、子 = 页面内操作按钮"两级组织，勾到根只给开门、勾到子才给按钮。
- 用户管理页面支持账号的增删改、有效期设置（到期自动禁用）与角色分配。

### 界面速览

**SSH 终端**（xterm.js，浏览器内直接操作远程机器）：

![SSH终端](img/ssh终端55.png)

**文件管理**（SFTP 上传下载、重命名、批量删除、跳转终端）：

![文件管理](img/文件管理666.png)

**应用管理**（预设启停命令，一键执行）：

![应用管理](img/应用管理4.png)

**Docker-Compose 管理**（一键启停、在线改配置）：

![Compose管理](img/compose33.png)

**镜像管理**：

![镜像管理](img/镜像2.png)

**用户管理**：

![用户管理](img/用户77.png)

---

## 系统架构

```
┌────────────────────────────────────────────────────────────┐
│                        浏览器                               │
│        Vaadin 24 服务端渲染 UI + xterm.js 终端              │
└──────────────┬──────────────────────────┬──────────────────┘
               │ HTTP（会话认证）          │ WebSocket
┌──────────────▼──────────────────────────▼──────────────────┐
│                   Spring Boot 3.5（内嵌 Tomcat，9095）      │
│                                                            │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌───────────────┐  │
│  │ UI 层    │ │ 安全层   │ │ 服务层   │ │ WebSocket 端点│  │
│  │ Vaadin   │ │ Spring   │ │ Docker / │ │ /ws/ssh       │  │
│  │ View 组件│ │ Security │ │ Compose /│ │ /ws/docker    │  │
│  │ MenuReg. │ │ SM3+BCrypt│ │ 日志/文件│ │ 终端令牌鉴权  │  │
│  └──────────┘ └──────────┘ └──────────┘ └───────────────┘  │
│         │              │            │                      │
│  ┌──────▼──────────────▼────────────▼───────────────────┐  │
│  │  MyBatis-Plus（分页插件按连接 URL 自动判定方言）      │  │
│  │  HikariCP 连接池                                     │  │
│  └──────┬───────────────────────────────────────────────┘  │
└─────────┼──────────────────────────────────────────────────┘
          │
   ┌──────▼──────┐    ┌─────────────────────────────────┐
   │ SQLite /    │    │ 远程主机（SSH 连接池复用）       │
   │ MySQL 8     │    │  sshj：SFTP / 远程命令 / pty    │
   │ 双方言兼容   │    │  docker / docker-compose CLI   │
   └─────────────┘    └─────────────────────────────────┘
```

关键设计点：

- **前端零独立部署**：Vaadin 服务端渲染，前端 TypeScript（CodeMirror 6 封装）由 Vite 构建打进 jar。
- **SSH 连接池**：所有远程操作复用连接池，避免频繁握手；SSH 库统一为 sshj（SFTP、远程命令、pty 终端一把抓）。
- **WebSocket 终端**：SSH 交互走 `/ws/ssh`，握手自带会话认证，另加一次性终端 token 校验。
- **数据库双兼容**：SQL 方言差异收敛在 `com.sl.db.DatabaseDialect`，业务代码不出现库名判断；分页插件从连接 URL 自动判定方言。
- **启动自引导**：`DbInitializer` 在每次启动时幂等建表补列、保证内置 admin 可登录、播种 RBAC 权限树，并在 `linux_cmd` 表为空时自动导入 Linux 命令种子数据（152 条）——全新环境不执行任何脚本即可启动。
- **日志统一 Log4j2**：所有 Spring Boot starter 的默认 logback 已显式排除，配置见 `log4j2.xml`。

技术栈清单：

| 层 | 组件 |
| --- | --- |
| UI | Vaadin 24.10、CodeMirror 6、xterm.js、ECharts |
| 后端 | Spring Boot 3.5.0、Spring Security、Java 21 |
| 数据 | MyBatis-Plus 3.5.12、HikariCP、SQLite / MySQL 8 |
| 远程操作 | sshj 0.40、docker CLI |
| 工具 | Hutool、FastJSON2、Apache POI（Excel 导出）、BouncyCastle（国密）、zxing（二维码） |

---

## 快速开始

### 环境要求

- JDK 21+
- Maven 3.9+（Node 环境由 Vaadin 插件自动准备）
- 目标机器如需远程管理：开放 SSH（22 端口），安装 docker / docker-compose（可选）

### 开发模式运行

```bash
mvn spring-boot:run
```

启动后访问 `http://localhost:9095/log`。默认激活 `dev` profile（SQLite），首次启动自动建库建表并导入内置数据（Linux 命令手册），无需任何手工初始化。

> 首次构建会下载前端依赖，耗时较长属正常现象。

### 生产打包

```bash
mvn clean package -Pproduction -DskipTests
java -jar target/lanyue-1.0-SNAPSHOT.jar
```

`-Pproduction` 会在 compile 阶段执行 `build-frontend`，把前端资源编译进 jar。

### 切换 MySQL 8

```bash
java -jar lanyue.jar --spring.profiles.active=mysql
```

MySQL 的建库、建表、导数据有一份完整的操作清单，见 [`src/main/resources/application-mysql.properties`](src/main/resources/application-mysql.properties) 文件内注释。表结构与种子数据同样由启动自引导兜底，不执行脚本也能直接起。

---

## 配置说明

所有配置位于 `src/main/resources/`：

| 文件 | 作用 |
| --- | --- |
| `application.properties` | 主配置：端口、上下文路径、公共数据源参数、MyBatis、上传限制 |
| `application-dev.properties` | dev profile：SQLite 环境 |
| `application-mysql.properties` | mysql profile：MySQL 8 数据源（含完整迁移注释） |
| `log4j2.xml` | 日志配置（唯一日志后端，禁止 logback） |
| `demo.sql` / `demo-mysql8.sql` | SQLite / MySQL 手工初始化脚本（含演示数据，改一处必改另一处） |
| `seed/linux_cmd.sql` | Linux 命令手册种子数据（由 `scripts/gen_linux_cmd_sql.py` 从 `linux_commands.csv` 生成，表空才导入） |

### 核心配置项

```properties
# 端口与上下文路径（访问地址 http://host:9095/log）
server.port=9095
server.servlet.context-path=/log

# profile 切换：dev=SQLite，mysql=MySQL 8
spring.profiles.active=dev

# 上传大小限制（文件管理用）
spring.servlet.multipart.max-file-size=200MB
spring.servlet.multipart.max-request-size=300MB
```

### 在线文件编辑白名单

哪些远程文件允许在浏览器里直接编辑，由这两项控制：

```properties
# 允许编辑的扩展名（不区分大小写，逗号分隔）
pure.text.type=txt,markdown,md,yml,yaml,conf,ini,log,csv,properties,sql,xml,html
# 单文件大小上限，支持 B/K/M/G 后缀
pure.text.type.maxsize=10M
```

### SQLite / MySQL 的选择

- **SQLite（dev）**：零配置，`demo.db` 文件即数据库，已开 WAL 模式。适合单机、小团队、演示环境。连接池固定 8 个连接（SQLite 单写多读，开大无益）。
- **MySQL 8（mysql）**：适合多人并发使用。URL 参数已按 Connector/J 最佳实践配置（utf8mb4、批量重写、时区）。建库时字符集**必须用 utf8mb4**，应用启动时会自动检查并告警。

---

## 默认账号

| 账号 | 来源 | 说明 |
| --- | --- | --- |
| `admin` | 启动自引导（`DbInitializer`） | 系统管理员，每次启动兜底保证存在且可登录，初始密码 `admin`，**登录后立即修改** |
| `test` / `developer` / `guest` / `temp` | `demo.sql`（手工导入才有） | 演示账号，覆盖不同权限与有效期场景 |

密码以摘要（SM3 / BCrypt）存储，数据库泄露不等于口令泄露。

---

## 目录结构

```
lanyue/
├── src/main/java/com/sl/
│   ├── Application.java          # 入口（@Push 全局配置也在这里）
│   ├── ui/                       # Vaadin 界面层
│   │   ├── MainView.java         # 主框架（菜单 + 多标签页）
│   │   ├── MenuRegistry.java     # 菜单静态定义（分组 / 权限）
│   │   ├── component/            # 通用组件（UiFactory、CodeEditor、Dialogs…）
│   │   ├── local/                # 本地应用管理各页面
│   │   ├── remote/               # 远程应用管理各页面
│   │   ├── docker/               # Docker / Compose 页面
│   │   ├── admin/                # 用户管理、角色管理
│   │   └── tool/                 # drawio、Linux 工具、各类小工具
│   ├── docker/                   # Docker / Compose 服务层
│   ├── security/                 # 认证授权（SM3 → BCrypt 渐进迁移）
│   ├── db/                       # 数据库方言抽象
│   ├── config/                   # DbInitializer、MyBatis、安全等配置
│   ├── controller/               # WebSocket 端点（SSH / Docker 终端）
│   ├── mapper/  entity/          # MyBatis-Plus 数据访问层
│   └── util/                     # 工具类
├── src/main/frontend/            # 前端 TS（CodeMirror 6 封装等）
├── src/main/resources/           # 配置、种子数据、日志配置
├── scripts/                      # 验证脚本与种子数据生成脚本
├── img/                          # README 截图
└── pom.xml
```

---

## 常见问题

**Q：启动后访问 9095 端口没反应？**
确认访问路径带上下文：`http://localhost:9095/log`，直接访问根路径会 404。

**Q：新增依赖后启动报 SLF4J 多 provider 警告？**
新依赖传递引入了 `spring-boot-starter-logging`（logback）。本项目唯一日志后端是 Log4j2，需在 `pom.xml` 给该依赖加 exclusion，参考 pom 中已有依赖的写法。

**Q：远程机器连不上？**
检查 SSH 端口、账号密码 / 密钥，以及防火墙。连接信息在「免登录服务器列表」中配置，支持密码和密钥两种认证方式。

**Q：从 SQLite 切到 MySQL 后老数据还在吗？**
不在。两个库独立，迁移步骤（建库 → 建表 → 逐表搬数据 → 切 profile）见 `application-mysql.properties` 内的清单。密码摘要与库无关，搬过去即可正常登录。

**Q：Linux 命令查询页面是空的？**
正常情况不会出现——种子数据（152 条）在启动时自动导入，条件是 `linux_cmd` 表为空。若表被人清空过，重启即自动重灌；若想改手册内容，改根目录 `linux_commands.csv` 后重跑 `scripts/gen_linux_cmd_sql.py`，再清空表重启即可。

**Q：nginx 作为反向代理服务器配置需要变更，默认不会转发 websocket**

```nginx
# http{} 块里加（若已有同名 map 则跳过）
map $http_upgrade $connection_upgrade {
    default upgrade;
    ''      close;
}

# 443 的 server 块里
location /log/ {
    proxy_pass http://127.0.0.1:9095/log;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;          # ← 根因修复
    proxy_set_header Connection $connection_upgrade; # ← 根因修复
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-Proto https;        # 修掉 http:// 重定向
    proxy_set_header X-Forwarded-Host $host;
    proxy_set_header X-Forwarded-Port 443;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_read_timeout 3600s;   # 默认 60s 会周期性掐断 push/终端长连接
    proxy_send_timeout 3600s;
    proxy_buffering off;
}
```

### 如何使用 drawio 嵌入功能

1. 在当前 jar 包所在目录下新建一个 drawio 目录，把最新版 drawio 源码中 webapps 下的所有代码直接复制到该目录下（该目录下包含 mxgraph、styles、js、images、math4、shapes、resources 等目录），重启项目。
