package com.sl.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * Linux 命令查询（Linux工具 → Linux命令查询）的一行命令手册。
 * <p>
 * 主键直接用命令名（{@code cmd_name}）：命令名天然唯一（一个名字一条记录），
 * 没必要再造一个纳秒 ID。搜索与详情弹窗都靠它定位。
 * <p>
 * 字段拆分的设计（比"说明"一个大字段好维护，页面也好排版）：
 * <ul>
 * <li>{@code cmd_summary} —— 一句话说清这条命令干什么；</li>
 * <li>{@code cmd_params} —— 参数说明，一行一个参数，格式「参数 + 两个空格 + 说明」，
 *     页面按等宽字体原样渲染；</li>
 * <li>{@code cmd_examples} —— 使用示例，一行一条，格式「命令 => 说明」，
 *     {@code =>} 是命令与说明的分隔符（竖线 | 在 shell 命令里太常见，不能当分隔符），
 *     第一条示例会作为弹窗输入框的默认命令；</li>
 * <li>{@code cmd_remark} —— 备注（坑点），可为空。</li>
 * </ul>
 */
@TableName("linux_cmd")
public class LinuxCmd {

    /** 命令名，主键，如 tar / grep / systemctl */
    @TableId(value = "cmd_name", type = IdType.INPUT)
    private String cmdName;

    /** 分类：压缩打包 / 文本处理 / 网络等，列表页分组排序用 */
    private String cmdCategory;

    /** 功能说明（一句话） */
    private String cmdSummary;

    /** 参数说明，一行一个：参数 + 两个空格 + 说明 */
    private String cmdParams;

    /** 使用示例，一行一条：命令 => 说明。第一条作为弹窗输入框的默认命令 */
    private String cmdExamples;

    /** 备注（坑点），可为空 */
    private String cmdRemark;

    public String getCmdName() {
        return cmdName;
    }

    public void setCmdName(String cmdName) {
        this.cmdName = cmdName;
    }

    public String getCmdCategory() {
        return cmdCategory;
    }

    public void setCmdCategory(String cmdCategory) {
        this.cmdCategory = cmdCategory;
    }

    public String getCmdSummary() {
        return cmdSummary;
    }

    public void setCmdSummary(String cmdSummary) {
        this.cmdSummary = cmdSummary;
    }

    public String getCmdParams() {
        return cmdParams;
    }

    public void setCmdParams(String cmdParams) {
        this.cmdParams = cmdParams;
    }

    public String getCmdExamples() {
        return cmdExamples;
    }

    public void setCmdExamples(String cmdExamples) {
        this.cmdExamples = cmdExamples;
    }

    public String getCmdRemark() {
        return cmdRemark;
    }

    public void setCmdRemark(String cmdRemark) {
        this.cmdRemark = cmdRemark;
    }
}
