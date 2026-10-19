package com.sl.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sl.entity.Permission;
import org.apache.ibatis.annotations.Mapper;

/**
 * {@code permissions} 表的 Mapper（权限树节点）。
 */
@Mapper
public interface PermissionDao extends BaseMapper<Permission> {
}
