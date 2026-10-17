package com.sl.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sl.entity.LinuxCmd;

/**
 * Linux 命令查询 Mapper。查询全部走 BaseMapper + QueryWrapper，无自定义 SQL。
 * 扫描由 {@code MybatisPlusConfig} 的 {@code @MapperScan("com.sl.mapper")} 覆盖。
 */
public interface LinuxCmdMapper extends BaseMapper<LinuxCmd> {
}
