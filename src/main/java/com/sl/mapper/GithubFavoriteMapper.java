package com.sl.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sl.entity.GithubFavoriteEntity;

/**
 * github优选 Mapper。增删改查全部走 BaseMapper 通用方法，无自定义 SQL。
 * 扫描由 {@code MybatisPlusConfig} 的 {@code @MapperScan("com.sl.mapper")} 覆盖。
 */
public interface GithubFavoriteMapper extends BaseMapper<GithubFavoriteEntity> {
}
