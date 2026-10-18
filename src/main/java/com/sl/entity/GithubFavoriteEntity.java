package com.sl.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * github优选（其他工具 → github优选）的一条项目记录。
 * <p>
 * 主键 {@code id_github} 取当前系统的纳秒值（{@code System.nanoTime()}），
 * 由应用生成、随表单一起插入，所以必须是 {@link IdType#INPUT}——
 * 让 MyBatis-Plus 别自作主张去生成 ID（与 {@link ScriptInfoEntity} 一致）。
 * <p>
 * 时间列沿用全项目的文本列约定（yyyy-MM-dd HH:mm:ss，见 demo.sql 头部注释），
 * SQLite / MySQL 8 双兼容不写两套 ResultMap。
 * <p>
 * 分类不是独立表：分类信息就存在每一行的 {@code cd_category} 上，页面临时聚合。
 * 数据量是「收藏夹」量级（几十条），为几十行数据维护第二张表还要管外键清理，
 * 得不偿失；真要膨胀到按千计，再拆表不迟。
 */
@TableName("github_favorite")
public class GithubFavoriteEntity {

    /** 项目 ID：创建时的纳秒值，字符串形式存库 */
    @TableId(value = "id_github", type = IdType.INPUT)
    private String idGithub;

    /** 项目名称（卡片标题） */
    private String projectName;

    /** GitHub 项目地址，形如 https://github.com/组织/仓库 */
    private String projectUrl;

    /** 项目介绍（卡片中部，超出三行折叠） */
    private String projectDesc;

    /** 分类；为空表示未分类，页面归到「未分类」组并排在最后 */
    private String cdCategory;

    /** 创建时间，文本列 yyyy-MM-dd HH:mm:ss */
    private String createTime;

    public String getIdGithub() {
        return idGithub;
    }

    public void setIdGithub(String idGithub) {
        this.idGithub = idGithub;
    }

    public String getProjectName() {
        return projectName;
    }

    public void setProjectName(String projectName) {
        this.projectName = projectName;
    }

    public String getProjectUrl() {
        return projectUrl;
    }

    public void setProjectUrl(String projectUrl) {
        this.projectUrl = projectUrl;
    }

    public String getProjectDesc() {
        return projectDesc;
    }

    public void setProjectDesc(String projectDesc) {
        this.projectDesc = projectDesc;
    }

    public String getCdCategory() {
        return cdCategory;
    }

    public void setCdCategory(String cdCategory) {
        this.cdCategory = cdCategory;
    }

    public String getCreateTime() {
        return createTime;
    }

    public void setCreateTime(String createTime) {
        this.createTime = createTime;
    }
}
