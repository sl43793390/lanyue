package com.sl.entity;

import com.baomidou.mybatisplus.annotation.TableName;

@TableName("log_path")
public class LogPath {

	private String idLoghost;
	private String idLogPath;
	private String nameLog;
	public String getIdLoghost() {
		return idLoghost;
	}
	public void setIdLoghost(String idLoghost) {
		this.idLoghost = idLoghost;
	}
	public String getIdLogPath() {
		return idLogPath;
	}
	public void setIdLogPath(String idLogPath) {
		this.idLogPath = idLogPath;
	}
	public String getNameLog() {
		return nameLog;
	}
	public void setNameLog(String nameLog) {
		this.nameLog = nameLog;
	}
	
	
}
