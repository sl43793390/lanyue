package com.sl.ui;

import com.sl.util.PermCodes;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * RBAC 权限目录：{@code permissions} 表的**唯一事实源**。
 * <p>
 * 树分两级，与需求一一对应：
 * <ul>
 * <li><b>根 = 菜单</b>：直接派生自 {@link MenuRegistry}（分组标题 + 菜单项标题 + 权限码），
 *     不在这里抄第二份——菜单结构与权限码必须同源，否则会出现「菜单上看得见、
 *     权限树里查不到」的漂移；</li>
 * <li><b>子 = 页面内部的操作按钮</b>：在这里按父菜单码登记（{@link #BUTTONS}），
 *     每一条都对应页面上一个真实按钮的权限判断点。</li>
 * </ul>
 * 启动时 {@code RbacService} 按本目录幂等播种：库里缺哪条就补哪条，
 * 已存在的只校准名称/层级/排序，不删用户自己加的行。
 */
public final class PermissionCatalog {

    private PermissionCatalog() {
    }

    /** 权限类型：菜单（根） */
    public static final String TYPE_MENU = "MENU";
    /** 权限类型：页面内操作按钮 */
    public static final String TYPE_BUTTON = "BUTTON";

    /**
     * 目录里的一条定义。
     *
     * @param code       权限码，唯一
     * @param name       显示名
     * @param type       MENU / BUTTON
     * @param parentCode 父权限码；根节点为空
     * @param order      同级排序，升序
     * @param desc       说明
     */
    public record Def(String code, String name, String type, String parentCode, int order, String desc) {
    }

    /**
     * 不在顶级菜单、但确实是独立页面的权限（应用管理里的 nginx / MySQL 子页）。
     * <p>
     * 它们从「免登录服务器列表 → 应用管理」进入，菜单栏上不占位置，但同样是
     * 可授权的最小单位——子页标签按各自权限码显隐，按钮判断也认这些码。
     * 与 {@link MenuRegistry} 派生出的菜单节点一样按根节点入树。
     */
    private static final List<Def> MENU_EXTRAS = List.of(
            new Def(PermCodes.NGINX_VIEW, "nginx管理", TYPE_MENU, null, 100,
                    "应用管理 · nginx 配置文件与站点管理"),
            new Def(PermCodes.MYSQL_VIEW, "MySQL管理", TYPE_MENU, null, 101,
                    "应用管理 · MySQL 账号与库表管理"),
            new Def(PermCodes.RFILE_VIEW, "远程文件管理", TYPE_MENU, null, 102,
                    "免登录服务器列表 · 远程文件上传下载"));

    /**
     * 页面内操作按钮权限，按父菜单码分组登记。
     * <p>
     * 只登记**页面上真实存在、且有权限判断点**的按钮；纯工具页（JSON 美化之类）
     * 没有需要保护的写操作，就只给菜单节点，不硬造按钮权限。
     */
    private static final List<Def> BUTTONS = List.of(
            // 本地文件管理：上传 / 删除
            new Def(PermCodes.FILE_UPLOAD, "上传文件", TYPE_BUTTON, PermCodes.FILE_VIEW, 1, "上传本地文件到服务器目录"),
            new Def(PermCodes.FILE_DELETE, "删除文件", TYPE_BUTTON, PermCodes.FILE_VIEW, 2, "删除本地文件或目录"),
            // jar 项目管理
            new Def(PermCodes.JAR_ADD, "新增 jar 项目", TYPE_BUTTON, PermCodes.JAR_VIEW, 1, "登记新的 jar 项目"),
            new Def(PermCodes.JAR_UPDATE, "修改 jar 项目", TYPE_BUTTON, PermCodes.JAR_VIEW, 2, "启动 / 停止 / 重启 / 编辑项目"),
            new Def(PermCodes.JAR_DELETE, "删除 jar 项目", TYPE_BUTTON, PermCodes.JAR_VIEW, 3, "注销 jar 项目登记"),
            new Def(PermCodes.JAR_UPLOAD, "上传 jar 包", TYPE_BUTTON, PermCodes.JAR_VIEW, 4, "向目标机上传新版本的 jar 包"),
            // Tomcat 管理
            new Def(PermCodes.TOMCAT_ADD, "新增 Tomcat", TYPE_BUTTON, PermCodes.TOMCAT_VIEW, 1, "登记新的 Tomcat 实例"),
            new Def(PermCodes.TOMCAT_UPDATE, "修改 Tomcat", TYPE_BUTTON, PermCodes.TOMCAT_VIEW, 2, "启动 / 停止 / 重启 / 编辑实例"),
            new Def(PermCodes.TOMCAT_DELETE, "删除 Tomcat", TYPE_BUTTON, PermCodes.TOMCAT_VIEW, 3, "注销 Tomcat 实例登记"),
            new Def(PermCodes.TOMCAT_UPLOAD, "上传 war 包", TYPE_BUTTON, PermCodes.TOMCAT_VIEW, 4, "向目标机上传 war 包并部署"),
            // 通用项目管理
            new Def(PermCodes.PROJECT_ADD, "新增通用项目", TYPE_BUTTON, PermCodes.PROJECT_VIEW, 1, "登记新的通用项目"),
            new Def(PermCodes.PROJECT_UPDATE, "修改通用项目", TYPE_BUTTON, PermCodes.PROJECT_VIEW, 2, "启动 / 停止 / 重启 / 编辑项目"),
            new Def(PermCodes.PROJECT_DELETE, "删除通用项目", TYPE_BUTTON, PermCodes.PROJECT_VIEW, 3, "注销通用项目登记"),
            new Def(PermCodes.PROJECT_UPLOAD, "上传项目文件", TYPE_BUTTON, PermCodes.PROJECT_VIEW, 4, "向目标机上传项目文件"),
            // 免登录服务器列表
            new Def(PermCodes.RSERVER_ADD, "添加机器", TYPE_BUTTON, PermCodes.RSERVER_VIEW, 1, "登记新的远程主机 / 新建分组"),
            new Def(PermCodes.RSERVER_UPDATE, "修改机器", TYPE_BUTTON, PermCodes.RSERVER_VIEW, 2, "编辑主机信息 / 移动分组"),
            new Def(PermCodes.RSERVER_DELETE, "删除机器", TYPE_BUTTON, PermCodes.RSERVER_VIEW, 3, "删除主机登记"),
            // Docker 容器和镜像
            new Def(PermCodes.DOCKER_DELETE, "删除容器/镜像/卷/网络", TYPE_BUTTON, PermCodes.DOCKER_VIEW, 1, "删除容器、镜像、数据卷、网络"),
            // Docker-Compose
            new Def(PermCodes.COMPOSE_ADD, "新增 Compose 项目", TYPE_BUTTON, PermCodes.COMPOSE_VIEW, 1, "创建 / 叠加 compose 项目"),
            new Def(PermCodes.COMPOSE_DELETE, "删除 Compose 项目", TYPE_BUTTON, PermCodes.COMPOSE_VIEW, 2, "删除 compose 项目登记"),
            // drawio 绘图
            new Def(PermCodes.DRAWIO_ADD, "新建图表", TYPE_BUTTON, PermCodes.DRAWIO_VIEW, 1, "新建 drawio 图表"),
            new Def(PermCodes.DRAWIO_DELETE, "删除图表", TYPE_BUTTON, PermCodes.DRAWIO_VIEW, 2, "删除 drawio 图表"),
            // 脚本管理
            new Def(PermCodes.SCRIPT_ADD, "新增脚本", TYPE_BUTTON, PermCodes.SCRIPT_VIEW, 1, "登记新的运维脚本"),
            new Def(PermCodes.SCRIPT_DELETE, "删除脚本", TYPE_BUTTON, PermCodes.SCRIPT_VIEW, 2, "删除运维脚本"),
            // github 优选
            new Def(PermCodes.GITHUB_ADD, "新增项目", TYPE_BUTTON, PermCodes.GITHUB_VIEW, 1, "收藏新的开源项目 / 新建分类"),
            new Def(PermCodes.GITHUB_UPDATE, "修改项目", TYPE_BUTTON, PermCodes.GITHUB_VIEW, 2, "编辑收藏 / 重命名分类"),
            new Def(PermCodes.GITHUB_DELETE, "删除项目", TYPE_BUTTON, PermCodes.GITHUB_VIEW, 3, "删除收藏 / 删除分类"),
            // 用户管理
            new Def(PermCodes.USER_ADD, "新增用户", TYPE_BUTTON, PermCodes.USER_VIEW, 1, "创建登录账号"),
            new Def(PermCodes.USER_UPDATE, "修改用户", TYPE_BUTTON, PermCodes.USER_VIEW, 2, "编辑资料 / 重置密码 / 启用禁用"),
            new Def(PermCodes.USER_DELETE, "删除用户", TYPE_BUTTON, PermCodes.USER_VIEW, 3, "删除登录账号"),
            // 角色管理
            new Def(PermCodes.ROLE_ADD, "新增角色", TYPE_BUTTON, PermCodes.ROLE_VIEW, 1, "创建角色并分配权限"),
            new Def(PermCodes.ROLE_UPDATE, "修改角色", TYPE_BUTTON, PermCodes.ROLE_VIEW, 2, "编辑角色与权限"),
            new Def(PermCodes.ROLE_DELETE, "删除角色", TYPE_BUTTON, PermCodes.ROLE_VIEW, 3, "删除角色"),
            // 应用管理子页：nginx / MySQL
            new Def(PermCodes.NGINX_UPDATE, "修改 nginx 配置", TYPE_BUTTON, PermCodes.NGINX_VIEW, 1, "编辑 / 重载 / 删除 nginx 配置"),
            new Def(PermCodes.MYSQL_UPDATE, "修改 MySQL", TYPE_BUTTON, PermCodes.MYSQL_VIEW, 1, "执行 SQL / 管理账号与库表"),
            // 远程文件管理
            new Def(PermCodes.RFILE_ADD, "新建目录", TYPE_BUTTON, PermCodes.RFILE_VIEW, 1, "在远程主机新建目录"),
            new Def(PermCodes.RFILE_UPDATE, "修改文件", TYPE_BUTTON, PermCodes.RFILE_VIEW, 2, "重命名 / 编辑远程文件"),
            new Def(PermCodes.RFILE_UPLOAD, "上传文件", TYPE_BUTTON, PermCodes.RFILE_VIEW, 3, "上传文件到远程主机"),
            new Def(PermCodes.RFILE_DELETE, "删除文件", TYPE_BUTTON, PermCodes.RFILE_VIEW, 4, "删除远程文件或目录"));

    /**
     * 新建角色时默认勾选的权限码。
     * <p>
     * 按需求固定为：概览（使用手册）、远程应用管理下的免登录服务器列表、
     * drawio、其他工具（全部 9 个工具页）、github 优选——都是**菜单级**勾选，
     * 不含页面内操作按钮。角色编辑弹窗打开时按这份清单预勾。
     */
    private static final Set<String> DEFAULT_ROLE_CODES = new LinkedHashSet<>(List.of(
            PermCodes.HOME_VIEW,
            PermCodes.RSERVER_VIEW,
            PermCodes.DRAWIO_VIEW,
            PermCodes.JSON_VIEW,
            PermCodes.BASE64_VIEW,
            PermCodes.ENCRYPT_VIEW,
            PermCodes.QRCODE_VIEW,
            PermCodes.REGEX_VIEW,
            PermCodes.CRON_VIEW,
            PermCodes.IMGCOMPRESS_VIEW,
            PermCodes.IMGCONVERT_VIEW,
            PermCodes.TEXTDIFF_VIEW,
            PermCodes.GITHUB_VIEW));

    /**
     * 完整目录（菜单 + 按钮），菜单节点从 {@link MenuRegistry} 派生，
     * 再补上不在顶级菜单里的页面（{@link #MENU_EXTRAS}）。
     */
    public static List<Def> all() {
        List<Def> result = new ArrayList<>();
        int order = 0;
        for (MenuRegistry.Group group : MenuRegistry.GROUPS) {
            for (MenuRegistry.Item item : group.items()) {
                String code = item.permission();
                // 理论上每个菜单项都配了权限码；空值视为"登录即可访问"，登记成码以免无法授权
                if (code == null || code.isBlank()) {
                    continue;
                }
                result.add(new Def(code, item.title(), TYPE_MENU, null, order++,
                        group.title() + " · " + item.title()));
            }
        }
        result.addAll(MENU_EXTRAS);
        result.addAll(BUTTONS);
        return result;
    }

    /** 新建角色的默认勾选清单（只读副本） */
    public static Set<String> defaultRoleCodes() {
        return new LinkedHashSet<>(DEFAULT_ROLE_CODES);
    }
}
