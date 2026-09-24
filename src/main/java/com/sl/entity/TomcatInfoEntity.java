package com.sl.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("tomcat_info")
public class TomcatInfoEntity {
	private String idHost;
	private String tomcatId;
	private String nameTomcat;
	private String tomcatPath;
	private String webappPath;
	private String tag;
	private String cdDescription;
	
	
	
	public String getCdDescription() {
		return cdDescription;
	}
	public void setCdDescription(String cdDescription) {
		this.cdDescription = cdDescription;
	}
	public String getNameTomcat() {
		return nameTomcat;
	}
	public void setNameTomcat(String nameTomcat) {
		this.nameTomcat = nameTomcat;
	}
	public String getTag() {
		return tag;
	}
	public void setTag(String tag) {
		this.tag = tag;
	}
	public String getTomcatId() {
		return tomcatId;
	}
	public void setTomcatId(String tomcatId) {
		this.tomcatId = tomcatId;
	}
	public String getWebappPath() {
		return webappPath;
	}
	public void setWebappPath(String webappPath) {
		this.webappPath = webappPath;
	}
	public String getTomcatPath() {
		return tomcatPath;
	}
	public void setTomcatPath(String tomcatPath) {
		this.tomcatPath = tomcatPath;
	}

	public String getIdHost() {
		return idHost;
	}

	public void setIdHost(String idHost) {
		this.idHost = idHost;
	}
}
