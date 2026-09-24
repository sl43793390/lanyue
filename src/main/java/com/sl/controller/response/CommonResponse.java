package com.sl.controller.response;

public class CommonResponse {

//	@ApiModelProperty(value="用户ID",required=true)
	protected String idCustomer;
	
//	@ApiModelProperty(value="交易结果",required=true)
	protected ResponseEnum status;
	
//	@ApiModelProperty(value="错误代码",required=true)
	protected String statusCode;
	
//	@ApiModelProperty(value="错误信息",required=true)
	protected String errorMsg;

	public String getErrorMsg() {
		return errorMsg;
	}

	public void setErrorMsg(String errorMsg) {
		this.errorMsg = errorMsg;
	}

	public String getIdCustomer() {
		return idCustomer;
	}

	public void setIdCustomer(String idCustomer) {
		this.idCustomer = idCustomer;
	}

	public ResponseEnum getStatus() {
		return status;
	}

	public void setStatus(ResponseEnum status) {
		this.status = status;
	}

	public String getStatusCode() {
		return statusCode;
	}

	public void setStatusCode(String statusCode) {
		this.statusCode = statusCode;
	}
}
