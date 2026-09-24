package com.sl.controller.response;

public class SnowFlakeResponse extends CommonResponse{

	private String snowId;

	public String getSnowId() {
		return snowId;
	}

	public void setSnowId(String snowId) {
		this.snowId = snowId;
	}

	public SnowFlakeResponse() {
		super();
		this.errorMsg="success";
		this.status=ResponseEnum.SUCCESS;
		this.statusCode="200";
	}
	/**
	 * 需要重写errorMsg
	 */
	public void setErrorMsg() {
		this.snowId = "";
		this.status=ResponseEnum.FAIL;
		this.statusCode="400";
	}
	
}
