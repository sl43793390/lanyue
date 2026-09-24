package com.sl.entity;

import java.util.Date;

public class Permission {
    private String permissionId;

    private String resourceName;

    private String action;

    private Integer version;
    
    private String idParent;
    
    private Integer nbrLevel;

    private Integer nbrOrder;
    private Date timestamp;

    public String getPermissionId() {
        return permissionId;
    }

    public void setPermissionId(String permissionId) {
        this.permissionId = permissionId;
    }

    public String getResourceName() {
		return resourceName;
	}

	public void setResourceName(String resourceName) {
		this.resourceName = resourceName;
	}

	public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action == null ? null : action.trim();
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }
    
    

    public String getIdParent() {
		return idParent;
	}

	public void setIdParent(String idParent) {
		this.idParent = idParent;
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

	public Date getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Date timestamp) {
        this.timestamp = timestamp;
    }
}