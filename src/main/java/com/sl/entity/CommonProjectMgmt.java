package com.sl.entity;

import com.baomidou.mybatisplus.annotation.TableName;

@TableName("common_project_mgmt")
public class CommonProjectMgmt {

	private String idHost;
	private String idProject;
	private String nameProject;
	private String cdPath;
	private String cmdStart;
	private String cmdStop;
	private String cmdRestart;
	private String cmdRefresh;
	private String cdDescription;
	private String cdTag;
	private String cmdStatus;
	private String cmdStatusSuccessKey;

	public String getIdHost() {
		return idHost;
	}

	public void setIdHost(String idHost) {
		this.idHost = idHost;
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

	public String getCdPath() {
		return cdPath;
	}

	public void setCdPath(String cdPath) {
		this.cdPath = cdPath;
	}

	public String getCmdStart() {
		return cmdStart;
	}

	public void setCmdStart(String cmdStart) {
		this.cmdStart = cmdStart;
	}

	public String getCmdStop() {
		return cmdStop;
	}

	public void setCmdStop(String cmdStop) {
		this.cmdStop = cmdStop;
	}

	public String getCmdRestart() {
		return cmdRestart;
	}

	public void setCmdRestart(String cmdRestart) {
		this.cmdRestart = cmdRestart;
	}

	public String getCmdRefresh() {
		return cmdRefresh;
	}

	public void setCmdRefresh(String cmdRefresh) {
		this.cmdRefresh = cmdRefresh;
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

	public String getCmdStatus() {
		return cmdStatus;
	}

	public void setCmdStatus(String cmdStatus) {
		this.cmdStatus = cmdStatus;
	}

	public String getCmdStatusSuccessKey() {
		return cmdStatusSuccessKey;
	}

	public void setCmdStatusSuccessKey(String cmdStatusSuccessKey) {
		this.cmdStatusSuccessKey = cmdStatusSuccessKey;
	}
}
