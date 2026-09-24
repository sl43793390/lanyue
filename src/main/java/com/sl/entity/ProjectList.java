package com.sl.entity;

import com.baomidou.mybatisplus.annotation.TableName;

@TableName("projects")
public class ProjectList {

	private String idHost;
	/**
	 * projects 表的真实主键是复合的 {@code (id_host, id_project)}（见 demo.sql / demo-mysql8.sql）。
	 * 这里<b>刻意不放 {@code @TableId}</b>：
	 * 旧版本曾把注解标在 idProject 上，MyBatis-Plus 会把 idProject 当唯一键——
	 * {@code updateById/deleteById} 生成 {@code WHERE id_project = ?}，
	 * 同一个项目 id 挂在不同主机上时会被连带改掉/删掉。
	 * 2026-09-24 jar 项目管理页（{@code com.sl.ui.local.JarProjectView}）迁移时已把唯一的
	 * {@code selectById} 用法改成 Wrapper 带全主键，注解随之移除。
	 * 没有注解后实体不再参与 MyBatis-Plus 的 id 生成，insert 时 idProject 原样落库（业务主键）；
	 * 启动时会多一条 {@code Can not find table primary key} 的 WARN，属预期现象，别去消它。
	 */
	private String idProject;
	private String nameProject;
	private String cdDescription;
	private String cdTag;
	
	private String cdCommand;
	private String jvmParam;
	private String jarParam;
	private String cdParentPath;
	private String jarName;
	
	
	
	public String getCdParentPath() {
		return cdParentPath;
	}
	public void setCdParentPath(String cdParentPath) {
		this.cdParentPath = cdParentPath;
	}
	public String getCdCommand() {
		return cdCommand;
	}
	public void setCdCommand(String cdCommand) {
		this.cdCommand = cdCommand;
	}
	public String getIdProject() {
		return idProject;
	}
	public void setIdProject(String idProject) {
		this.idProject = idProject;
	}
	public String getNameProject() {
		return nameProject;
	}
	public void setNameProject(String nameProject) {
		this.nameProject = nameProject;
	}
	public String getCdDescription() {
		return cdDescription;
	}
	public void setCdDescription(String cdDescription) {
		this.cdDescription = cdDescription;
	}
	public String getCdTag() {
		return cdTag;
	}
	public void setCdTag(String cdTag) {
		this.cdTag = cdTag;
	}
	public String getJvmParam() {
		return jvmParam;
	}
	public void setJvmParam(String jvmParam) {
		this.jvmParam = jvmParam;
	}
	public String getJarParam() {
		return jarParam;
	}
	public void setJarParam(String jarParam) {
		this.jarParam = jarParam;
	}
	public String getJarName() {
		return jarName;
	}
	public void setJarName(String jarName) {
		this.jarName = jarName;
	}
	public String getIdHost() {
		return idHost;
	}
	public void setIdHost(String idHost) {
		this.idHost = idHost;
	}

	@Override
	public String toString() {
		return "ProjectList{" +
				"idHost='" + idHost + '\'' +
				", idProject='" + idProject + '\'' +
				", nameProject='" + nameProject + '\'' +
				", cdDescription='" + cdDescription + '\'' +
				", cdTag='" + cdTag + '\'' +
				", cdCommand='" + cdCommand + '\'' +
				", jvmParam='" + jvmParam + '\'' +
				", jarParam='" + jarParam + '\'' +
				", cdParentPath='" + cdParentPath + '\'' +
				", jarName='" + jarName + '\'' +
				'}';
	}
}
