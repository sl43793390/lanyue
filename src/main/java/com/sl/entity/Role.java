package com.sl.entity;

import java.util.Date;
import java.util.HashSet;

public class Role {
    private String roleId;

    private String roleName;
    
    private String roleDesc;

    private Integer version;

    private Date timestamp;
    
    private HashSet<Permission> permissions;

    
    
    public String getRoleDesc() {
		return roleDesc;
	}

	public void setRoleDesc(String roleDesc) {
		this.roleDesc = roleDesc;
	}

	public HashSet<Permission> getPermissions() {
		return permissions;
	}

	public void setPermissions(HashSet<Permission> permissions) {
		this.permissions = permissions;
	}

	public String getRoleId() {
        return roleId;
    }

    public void setRoleId(String roleId) {
        this.roleId = roleId;
    }

    public String getRoleName() {
        return roleName;
    }

    public void setRoleName(String roleName) {
        this.roleName = roleName == null ? null : roleName.trim();
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public Date getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Date timestamp) {
        this.timestamp = timestamp;
    }
}