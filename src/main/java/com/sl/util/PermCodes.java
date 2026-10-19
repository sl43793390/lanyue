package com.sl.util;

/**
 * RBAC 权限码常量。
 * <p>
 * 编码规则 {@code <模块>:<动作>}：{@code :view} 是页面（菜单）本身的访问权，
 * 其余是页面内部的操作按钮权。整棵树由 {@code com.sl.service.PermissionCatalog}
 * 按「根 = 菜单、子 = 页面内操作按钮」两级组织，落库在 {@code permissions} 表。
 * <p>
 * 与旧模型的关系：{@code users.permission} 列里那套全局码（{@code ADD/DELETE/...}）
 * 继续有效，作为历史数据的兜底——有效权限 = 角色权限 ∪ 历史权限列，
 * {@code ALL}（内置管理员）仍然命中全部权限码。新页面只认这里的码。
 */
public final class PermCodes {

    private PermCodes() {
    }

    // ---- 概览 ----
    public static final String HOME_VIEW = "home:view";

    // ---- 本地应用管理 ----
    public static final String LOGSEARCH_VIEW = "logsearch:view";
    public static final String FILE_VIEW = "file:view";
    public static final String FILE_UPLOAD = "file:upload";
    public static final String FILE_DELETE = "file:delete";
    public static final String JAR_VIEW = "jar:view";
    public static final String JAR_ADD = "jar:add";
    public static final String JAR_UPDATE = "jar:update";
    public static final String JAR_DELETE = "jar:delete";
    public static final String JAR_UPLOAD = "jar:upload";
    public static final String TOMCAT_VIEW = "tomcat:view";
    public static final String TOMCAT_ADD = "tomcat:add";
    public static final String TOMCAT_UPDATE = "tomcat:update";
    public static final String TOMCAT_DELETE = "tomcat:delete";
    public static final String TOMCAT_UPLOAD = "tomcat:upload";
    public static final String PROJECT_VIEW = "project:view";
    public static final String PROJECT_ADD = "project:add";
    public static final String PROJECT_UPDATE = "project:update";
    public static final String PROJECT_DELETE = "project:delete";
    public static final String PROJECT_UPLOAD = "project:upload";

    // ---- 远程应用管理 ----
    public static final String RLOGSEARCH_VIEW = "rlogsearch:view";
    public static final String RSERVER_VIEW = "rserver:view";
    public static final String RSERVER_ADD = "rserver:add";
    public static final String RSERVER_UPDATE = "rserver:update";
    public static final String RSERVER_DELETE = "rserver:delete";

    // ---- 应用管理（免登录服务器列表 → 应用管理 内的子页，不在顶级菜单） ----
    public static final String NGINX_VIEW = "nginx:view";
    public static final String NGINX_UPDATE = "nginx:update";
    public static final String MYSQL_VIEW = "mysql:view";
    public static final String MYSQL_UPDATE = "mysql:update";
    /** 远程文件管理（免登录服务器列表 → 文件管理） */
    public static final String RFILE_VIEW = "rfile:view";
    public static final String RFILE_ADD = "rfile:add";
    public static final String RFILE_UPDATE = "rfile:update";
    public static final String RFILE_UPLOAD = "rfile:upload";
    public static final String RFILE_DELETE = "rfile:delete";

    // ---- Docker 管理 ----
    public static final String DOCKER_VIEW = "docker:view";
    public static final String DOCKER_DELETE = "docker:delete";
    public static final String COMPOSE_VIEW = "compose:view";
    public static final String COMPOSE_ADD = "compose:add";
    public static final String COMPOSE_DELETE = "compose:delete";

    // ---- drawio ----
    public static final String DRAWIO_VIEW = "drawio:view";
    public static final String DRAWIO_ADD = "drawio:add";
    public static final String DRAWIO_DELETE = "drawio:delete";

    // ---- Linux 工具 ----
    public static final String LINUXOPS_VIEW = "linuxops:view";
    public static final String LINUXCMD_VIEW = "linuxcmd:view";
    public static final String SCRIPT_VIEW = "script:view";
    public static final String SCRIPT_ADD = "script:add";
    public static final String SCRIPT_DELETE = "script:delete";
    public static final String SCRIPTMON_VIEW = "scriptmon:view";

    // ---- 其他工具 ----
    public static final String JSON_VIEW = "json:view";
    public static final String BASE64_VIEW = "base64:view";
    public static final String ENCRYPT_VIEW = "encrypt:view";
    public static final String QRCODE_VIEW = "qrcode:view";
    public static final String REGEX_VIEW = "regex:view";
    public static final String CRON_VIEW = "cron:view";
    public static final String IMGCOMPRESS_VIEW = "imgcompress:view";
    public static final String IMGCONVERT_VIEW = "imgconvert:view";
    public static final String TEXTDIFF_VIEW = "textdiff:view";

    // ---- github 优选 ----
    public static final String GITHUB_VIEW = "github:view";
    public static final String GITHUB_ADD = "github:add";
    public static final String GITHUB_UPDATE = "github:update";
    public static final String GITHUB_DELETE = "github:delete";

    // ---- 系统管理 ----
    public static final String USER_VIEW = "user:view";
    public static final String USER_ADD = "user:add";
    public static final String USER_UPDATE = "user:update";
    public static final String USER_DELETE = "user:delete";
    public static final String ROLE_VIEW = "role:view";
    public static final String ROLE_ADD = "role:add";
    public static final String ROLE_UPDATE = "role:update";
    public static final String ROLE_DELETE = "role:delete";
}
