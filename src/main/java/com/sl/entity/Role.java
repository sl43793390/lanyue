package com.sl.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sl.mapper.handler.TextDateTypeHandler;
import com.baomidou.mybatisplus.annotation.TableField;

import java.util.Date;

/**
 * 角色，对应 {@code roles} 表。
 * <p>
 * 角色是权限的集合：{@code role_permissions} 决定它持有哪些权限码，
 * {@code user_roles} 决定哪些用户拥有它。有效权限取用户所有角色的并集，
 * 再并上 {@code users.permission} 历史权限列（{@code ALL} 命中全部）。
 * <p>
 * {@code flag_builtin = '1'} 是内置角色（如「系统管理员」），不允许删除——
 * 与内置管理员账号同理，删掉就没有人能恢复全权配置了。
 */
@TableName(value = "roles", autoResultMap = true)
public class Role {

    /** 内置角色「系统管理员」的角色 ID，启动时由 RbacService 保证存在 */
    public static final String BUILTIN_ADMIN_ROLE_ID = "role_admin";
    /** 内置角色「系统管理员」的角色名 */
    public static final String BUILTIN_ADMIN_ROLE_NAME = "系统管理员";
    /** 演示用的普通角色，权限 = 新建角色时的默认勾选项 */
    public static final String DEFAULT_ROLE_ID = "role_default";
    public static final String DEFAULT_ROLE_NAME = "普通用户";

    @TableId(value = "id_role")
    private String roleId;

    private String nameRole;

    private String cdDesc;

    /** '1' = 内置角色，不可删除 */
    private String flagBuiltin;

    @TableField(value = "create_time", typeHandler = TextDateTypeHandler.class)
    private Date createTime;

    /** 该角色持有的权限码，列表/编辑页展示用，不是表字段 */
    @TableField(exist = false)
    private java.util.Set<String> permissionCodes;

    /** 关联用户数，列表展示用，不是表字段 */
    @TableField(exist = false)
    private Integer userCount;

    public String getRoleId() {
        return roleId;
    }

    public void setRoleId(String roleId) {
        this.roleId = roleId == null ? null : roleId.trim();
    }

    public String getNameRole() {
        return nameRole;
    }

    public void setNameRole(String nameRole) {
        this.nameRole = nameRole == null ? null : nameRole.trim();
    }

    public String getCdDesc() {
        return cdDesc;
    }

    public void setCdDesc(String cdDesc) {
        this.cdDesc = cdDesc;
    }

    public String getFlagBuiltin() {
        return flagBuiltin;
    }

    public void setFlagBuiltin(String flagBuiltin) {
        this.flagBuiltin = flagBuiltin == null ? null : flagBuiltin.trim();
    }

    public boolean isBuiltin() {
        return "1".equals(flagBuiltin);
    }

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }

    public java.util.Set<String> getPermissionCodes() {
        return permissionCodes;
    }

    public void setPermissionCodes(java.util.Set<String> permissionCodes) {
        this.permissionCodes = permissionCodes;
    }

    public Integer getUserCount() {
        return userCount;
    }

    public void setUserCount(Integer userCount) {
        this.userCount = userCount;
    }
}
