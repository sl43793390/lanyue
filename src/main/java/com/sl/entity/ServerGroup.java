package com.sl.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;

/**
 * 免登录服务器列表的分组定义。
 * <p>
 * 只有一列 {@code group_name}（主键）：分组就是个名字，机器归属存在
 * {@code connection_info.cd_group} 上，这里只负责「有哪些分组、重名查重」。
 * 「默认分组」是虚拟的，不往这张表里写——下拉框永远先给默认分组，
 * 表里的行只存用户显式创建的分组。
 */
public class ServerGroup {

	/** 分组名，主键。用户手输，界面限制长度与重复。 */
	@TableId(value = "group_name", type = IdType.INPUT)
	private String groupName;

	public ServerGroup() {
	}

	public ServerGroup(String groupName) {
		this.groupName = groupName;
	}

	public String getGroupName() {
		return groupName;
	}

	public void setGroupName(String groupName) {
		this.groupName = groupName;
	}
}
