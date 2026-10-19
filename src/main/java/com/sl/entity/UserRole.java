package com.sl.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 用户 - 角色关联，对应 {@code user_roles} 表，复合主键。
 * <p>
 * 列名是 {@code id_user} / {@code id_role}（本项目一贯的 id_ 前缀风格），
 * 而字段是 {@code userId} / {@code roleId}——不加 {@link TableField} 的话
 * MyBatis-Plus 按驼峰转下划线会拼出 {@code role_id}，SQLite 直接报
 * {@code no such column}。这类"建表成功、查询才炸"的错只在运行期暴露，务必显式映射。
 */
@TableName(value = "user_roles")
public class UserRole {

    @TableField("id_user")
    private String userId;

    @TableField("id_role")
    private String roleId;

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId == null ? null : userId.trim();
    }

    public String getRoleId() {
        return roleId;
    }

    public void setRoleId(String roleId) {
        this.roleId = roleId == null ? null : roleId.trim();
    }
}
