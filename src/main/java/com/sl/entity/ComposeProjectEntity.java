package com.sl.entity;

import com.baomidou.mybatisplus.annotation.TableName;

/**
 * Docker-Compose 项目登记表（compose 管理 → 所有用户创建过的项目）。
 * <p>
 * 表结构与 {@code DbInitializer.TABLES} 里的 {@code compose_project} 定义
 * 以及 demo.sql / demo-mysql8.sql 逐列对应，改任何一处必须同步另外两处。
 * <p>
 * 复合主键 {@code (id_host, name)}：同一台主机上 compose 项目名唯一
 * （它就是 {@code -p} 的值，重名会互相踩容器名前缀），跨主机则天然隔离。
 * MyBatis-Plus 不支持复合 @TableId，这里与 {@code ConnectionInfo} 同一套做法：
 * 实体不标主键，条件查询 / 更新走 QueryWrapper。
 */
@TableName("compose_project")
public class ComposeProjectEntity {

    /** 目标主机标识，格式 host:port（DockerExecutor.hostLabel()） */
    private String idHost;
    /** compose 项目名（容器名 / 卷名前缀），同主机唯一 */
    private String name;
    /** 项目目录（目标机绝对路径） */
    private String cdDirectory;
    /** 参与叠加的 compose 文件名，逗号分隔，顺序即 -f 顺序 */
    private String cdFiles;
    /** 项目描述 */
    private String cdDescription;
    /** 创建人（users.id_user） */
    private String idUser;
    /** 创建时间，文本列 yyyy-MM-dd HH:mm:ss */
    private String createTime;

    public String getIdHost() {
        return idHost;
    }

    public void setIdHost(String idHost) {
        this.idHost = idHost;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCdDirectory() {
        return cdDirectory;
    }

    public void setCdDirectory(String cdDirectory) {
        this.cdDirectory = cdDirectory;
    }

    public String getCdFiles() {
        return cdFiles;
    }

    public void setCdFiles(String cdFiles) {
        this.cdFiles = cdFiles;
    }

    public String getCdDescription() {
        return cdDescription;
    }

    public void setCdDescription(String cdDescription) {
        this.cdDescription = cdDescription;
    }

    public String getIdUser() {
        return idUser;
    }

    public void setIdUser(String idUser) {
        this.idUser = idUser;
    }

    public String getCreateTime() {
        return createTime;
    }

    public void setCreateTime(String createTime) {
        this.createTime = createTime;
    }
}
