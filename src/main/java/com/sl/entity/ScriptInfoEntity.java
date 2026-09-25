package com.sl.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 脚本管理（其他工具 → 脚本管理）的一行脚本。
 * <p>
 * 主键 {@code id_script} 取当前系统的纳秒值（{@code System.nanoTime()}），
 * 由应用生成、随表单一起插入，所以必须是 {@link IdType#INPUT}——
 * 让 MyBatis-Plus 别自作主张去生成 ID。
 * <p>
 * 时间列沿用全项目的文本列约定（yyyy-MM-dd HH:mm:ss，见 demo.sql 头部注释），
 * SQLite / MySQL 8 双兼容不写两套 ResultMap。
 */
@TableName("script_mgmt")
public class ScriptInfoEntity {

    /** 脚本 ID：创建时的纳秒值，字符串形式存库 */
    @TableId(value = "id_script", type = IdType.INPUT)
    private String idScript;

    /** 脚本名称 */
    private String scriptName;

    /** 脚本说明 */
    private String scriptDesc;

    /** 脚本内容（shell 全文） */
    private String scriptContent;

    /** 创建时间，文本列 yyyy-MM-dd HH:mm:ss */
    private String createTime;

    public String getIdScript() {
        return idScript;
    }

    public void setIdScript(String idScript) {
        this.idScript = idScript;
    }

    public String getScriptName() {
        return scriptName;
    }

    public void setScriptName(String scriptName) {
        this.scriptName = scriptName;
    }

    public String getScriptDesc() {
        return scriptDesc;
    }

    public void setScriptDesc(String scriptDesc) {
        this.scriptDesc = scriptDesc;
    }

    public String getScriptContent() {
        return scriptContent;
    }

    public void setScriptContent(String scriptContent) {
        this.scriptContent = scriptContent;
    }

    public String getCreateTime() {
        return createTime;
    }

    public void setCreateTime(String createTime) {
        this.createTime = createTime;
    }
}
