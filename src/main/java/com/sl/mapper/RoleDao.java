package com.sl.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sl.entity.Role;
import org.apache.ibatis.annotations.Mapper;

/**
 * {@code roles} 表的 Mapper。
 */
@Mapper
public interface RoleDao extends BaseMapper<Role> {
}
