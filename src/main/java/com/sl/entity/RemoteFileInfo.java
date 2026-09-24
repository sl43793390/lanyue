package com.sl.entity;

import java.util.Date;

public class RemoteFileInfo {

    private String fileName;
    private String permission;
    private String userName;
    private String userGroup;
    private String size;
    private String lastModify;

    private String parentPath;
    private String currentPath;
    private Boolean isFile;


    public RemoteFileInfo() {
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getPermission() {
        return permission;
    }

    public void setPermission(String permission) {
        this.permission = permission;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public String getUserGroup() {
        return userGroup;
    }

    public void setUserGroup(String userGroup) {
        this.userGroup = userGroup;
    }

    public String getSize() {
        return size;
    }

    public void setSize(String size) {
        this.size = size;
    }

    public String getLastModify() {
        return lastModify;
    }

    public void setLastModify(String lastModify) {
        this.lastModify = lastModify;
    }

    public String getCurrentPath() {
        return currentPath;
    }

    public void setCurrentDir(String currentPath) {
        this.currentPath = currentPath;
    }

    public Boolean getIsFile() {
        return isFile;
    }

    public void setIsFile(Boolean isFile) {
        this.isFile = isFile;
    }

    public String getParentPath() {
        return parentPath;
    }

    public void setParentPath(String parentPath) {
        this.parentPath = parentPath;
    }

    @Override
    public String toString() {
        return "RemoteFileInfo{" +
                "fileName='" + fileName + '\'' +
                ", permission='" + permission + '\'' +
                ", userName='" + userName + '\'' +
                ", userGroup='" + userGroup + '\'' +
                ", size='" + size + '\'' +
                ", lastModify='" + lastModify + '\'' +
                ", parentPath='" + parentPath + '\'' +
                ", currentPath='" + currentPath + '\'' +
                ", isFile=" + isFile +
                '}';
    }
}
