package com.sl.entity;

import java.util.Objects;

import com.baomidou.mybatisplus.annotation.TableField;

public class ConnectionInfo {

	private String idHost;
	private String cdPort;
	private String idUser;
	private String cdPassword;
	private String cdKeyPath;
	private String cdLogpath;
	/**
	 * 备注列在库里叫 {@code cd_desc}（DESC 是 MySQL 8 保留字，建表时已改名，
	 * 见 demo.sql 与 DbInitializer 的幂等迁移）；MyBatis-Plus 按驼峰转下划线
	 * 会得到 desc，与实际列名对不上，所以必须显式指定。
	 */
	@TableField("cd_desc")
	private String desc;
	
	
	
	public ConnectionInfo(String idHost, String cdPort, String idUser, String cdPassword, String cdKeyPath) {
		super();
		this.idHost = idHost;
		this.cdPort = cdPort;
		this.idUser = idUser;
		this.cdPassword = cdPassword;
		this.cdKeyPath = cdKeyPath;
	}

	public ConnectionInfo(String idHost, String cdPort, String idUser, String cdPassword, String cdKeyPath,String desc) {
		super();
		this.idHost = idHost;
		this.cdPort = cdPort;
		this.idUser = idUser;
		this.cdPassword = cdPassword;
		this.cdKeyPath = cdKeyPath;
		this.desc = desc;
	}

	public ConnectionInfo(String idHost, String cdPort, String idUser, String cdPassword, String cdKeyPath,String cdLogpath,String desc) {
		super();
		this.idHost = idHost;
		this.cdPort = cdPort;
		this.idUser = idUser;
		this.cdPassword = cdPassword;
		this.cdKeyPath = cdKeyPath;
		// 原来是 this.desc = desc; 而 cdLogpath 这个入参被直接丢弃了
		this.cdLogpath = cdLogpath;
		this.desc = desc;
	}
	public String getIdHost() {
		return idHost;
	}
	public void setIdHost(String idHost) {
		this.idHost = idHost;
	}
	public String getCdPort() {
		return cdPort;
	}
	public void setCdPort(String cdPort) {
		this.cdPort = cdPort;
	}
	public String getIdUser() {
		return idUser;
	}
	public void setIdUser(String idUser) {
		this.idUser = idUser;
	}
	public String getCdPassword() {
		return cdPassword;
	}
	public void setCdPassword(String cdPassword) {
		this.cdPassword = cdPassword;
	}
	public String getCdKeyPath() {
		return cdKeyPath;
	}
	public void setCdKeyPath(String cdKeyPath) {
		this.cdKeyPath = cdKeyPath;
	}
	public String getCdLogpath() {
		return cdLogpath;
	}
	public void setCdLogpath(String cdLogpath) {
		this.cdLogpath = cdLogpath;
	}

	public String getDesc() {
		return desc;
	}

	public void setDesc(String desc) {
		this.desc = desc;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || getClass() != o.getClass()) {
			return false;
		}
		ConnectionInfo that = (ConnectionInfo) o;
		return idHost.equals(that.idHost) && cdPort.equals(that.cdPort);
	}

	@Override
	public int hashCode() {
		return Objects.hash(idHost, cdPort);
	}
}
