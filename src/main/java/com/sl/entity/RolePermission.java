package com.sl.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 角色 - 权限关联，对应 {@code role_permissions} 表，复合主键。
 * <p>
 * 列名显式映射的理由同 {@link UserRole}：{@code roleId} 的默认下划线化是
 * {@code role_id}，与实际的 {@code id_role} 对不上。
 */
@TableName(value = "role_permissions")
public class RolePermission {

    @TableField("id_role")
    private String roleId;

    @TableField("id_perm")
    private String permId;

    public String getRoleId() {
        return roleId;
    }

    public void setRoleId(String roleId) {
        this.roleId = roleId == null ? null : roleId.trim();
    }

    public String getPermId() {
        return permId;
    }

    public void setPermId(String permId) {
        this.permId = permId == null ? null : permId.trim();
    }
}
