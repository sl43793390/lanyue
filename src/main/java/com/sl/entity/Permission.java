package com.sl.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sl.mapper.handler.TextDateTypeHandler;

import java.util.Date;

/**
 * 权限定义，对应 {@code permissions} 表，RBAC 的权限树节点。
 * <p>
 * 树分两级：<b>根是菜单</b>（{@code cd_type = MENU}，{@code nbr_level = 1}，
 * 对应菜单栏里的一个功能页，码形如 {@code user:view}）；<b>子节点是页面内部的操作按钮</b>
 * （{@code cd_type = BUTTON}，{@code nbr_level = 2}，码形如 {@code user:add}）。
 * 层级关系只靠 {@code id_parent} 表达，不建第三层——按钮没有子权限。
 * <p>
 * {@code cd_perm} 是全表唯一的权限码，程序里所有判断都认它（见
 * {@link com.sl.util.PermCodes}），{@code id_perm} 只是代理主键。
 */
@TableName(value = "permissions", autoResultMap = true)
public class Permission {

    @TableId(value = "id_perm")
    private String permId;

    /** 权限码，唯一，如 user:view / user:add */
    private String cdPerm;

    /** 显示名，如「用户管理」「新增用户」 */
    private String namePerm;

    /** 父权限 id_perm；根节点为空 */
    private String idParent;

    /** 1 = 菜单（根），2 = 页面内操作按钮 */
    private Integer nbrLevel;

    /** 同级排序，升序 */
    private Integer nbrOrder;

    /** MENU / BUTTON */
    private String cdType;

    private String cdDesc;

    @TableField(value = "create_time", typeHandler = TextDateTypeHandler.class)
    private Date createTime;

    /** 树形展示/勾选时挂在节点上的子权限，不是表字段 */
    @TableField(exist = false)
    private java.util.List<Permission> children;

    public String getPermId() {
        return permId;
    }

    public void setPermId(String permId) {
        this.permId = permId == null ? null : permId.trim();
    }

    public String getCdPerm() {
        return cdPerm;
    }

    public void setCdPerm(String cdPerm) {
        this.cdPerm = cdPerm == null ? null : cdPerm.trim();
    }

    public String getNamePerm() {
        return namePerm;
    }

    public void setNamePerm(String namePerm) {
        this.namePerm = namePerm == null ? null : namePerm.trim();
    }

    public String getIdParent() {
        return idParent;
    }

    public void setIdParent(String idParent) {
        this.idParent = idParent == null ? null : idParent.trim();
    }

    public Integer getNbrLevel() {
        return nbrLevel;
    }

    public void setNbrLevel(Integer nbrLevel) {
        this.nbrLevel = nbrLevel;
    }

    public Integer getNbrOrder() {
        return nbrOrder;
    }

    public void setNbrOrder(Integer nbrOrder) {
        this.nbrOrder = nbrOrder;
    }

    public String getCdType() {
        return cdType;
    }

    public void setCdType(String cdType) {
        this.cdType = cdType == null ? null : cdType.trim();
    }

    public String getCdDesc() {
        return cdDesc;
    }

    public void setCdDesc(String cdDesc) {
        this.cdDesc = cdDesc;
    }

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }

    public java.util.List<Permission> getChildren() {
        return children;
    }

    public void setChildren(java.util.List<Permission> children) {
        this.children = children;
    }

    /** 是否为菜单（根）节点 */
    public boolean isMenu() {
        return "MENU".equalsIgnoreCase(cdType);
    }
}
