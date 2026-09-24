package com.sl.controller.response;

public enum ResponseEnum {

	SUCCESS("success"),
	FAIL("fail");

	private String status;

	public String getStatus() {
		return status;
	}

	public void setStatus(String status) {
		this.status = status;
	}

	private ResponseEnum(String status) {
		this.status = status;
	}
	
	
}
