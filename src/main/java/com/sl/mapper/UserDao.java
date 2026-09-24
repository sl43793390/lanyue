package com.sl.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sl.entity.User;
import org.apache.ibatis.annotations.Mapper;

/**
 * {@code users} 表的 Mapper。
 * <p>
 * 只继承 {@link BaseMapper}，全部 CRUD 由 MyBatis-Plus 注入；
 * 复杂的条件查询在注解里写，不另开 XML（项目里没有 mapper XML 目录）。
 */
@Mapper
public interface UserDao extends BaseMapper<User> {

}
