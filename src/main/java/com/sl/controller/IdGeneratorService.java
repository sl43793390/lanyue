package com.sl.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sl.controller.response.SnowFlakeResponse;

import cn.hutool.core.lang.Snowflake;

@RestController
@RequestMapping("/idGen")
public class IdGeneratorService {

	/**
	 * 构造函数
	 * 
	 * @param workerId
	 *            工作ID (0~31)
	 * @param datacenterId
	 *            样例：http://localhost:8080/log/idGen/snowFlake?workerId=1&dataCenterId=1 数据中心ID (0~31)
	 */
	@GetMapping("/snowFlake")
	private SnowFlakeResponse snowFlake(long workerId, long dataCenterId) {

		SnowFlakeResponse resp = new SnowFlakeResponse();
		String nextIdStr;
		try {
			Snowflake sn = new Snowflake(workerId, dataCenterId);
			nextIdStr = sn.nextIdStr();
			resp.setSnowId(nextIdStr);
		} catch (Exception e) {
			e.printStackTrace();
			resp.setErrorMsg();
			resp.setErrorMsg("请求参数有误，请检查");
		}
		return resp;
	}

}
