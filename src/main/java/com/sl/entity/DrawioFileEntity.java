package com.sl.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * drawio绘图（其他工具 → drawio绘图）的一张图表。
 * <p>
 * 图表本体是 draw.io 的 mxfile XML（纯文本），直接存库——不落文件系统的原因：
 * 它天然是结构化数据，跟着用户走（{@code id_user} 即 {@code users.id_user}，
 * 登录名即主键），按用户查列表、改名、删行都是一条 SQL 的事；拆成
 * {@code fileStorage/<用户>/xxx.drawio} 反而要自己处理目录清理和并发写。
 * <p>
 * 主键 {@code id_file} 取当前系统的纳秒值（{@code System.nanoTime()}），
 * 由应用生成、随插入语句一起进库，所以必须是 {@link IdType#INPUT}——
 * 让 MyBatis-Plus 别自作主张去生成 ID。
 * <p>
 * {@code file_content} 的长度（VARCHAR(16000)，utf8mb4 下约 64KB）受 MySQL 行大小
 * 限制约束（65535 字节），是两库兼容下的实际上限；普通流程图的 XML 在几 KB 量级，
 * 超限属于极端场景，保存时会被数据库报错拦下来并提示。
 * <p>
 * 时间列沿用全项目的文本列约定（yyyy-MM-dd HH:mm:ss，见 demo.sql 头部注释），
 * SQLite / MySQL 8 双兼容不写两套 ResultMap。
 */
@TableName("drawio_file")
public class DrawioFileEntity {

    /** 图表 ID：创建时的纳秒值，字符串形式存库 */
    @TableId(value = "id_file", type = IdType.INPUT)
    private String idFile;

    /** 归属用户：users.id_user（登录名），用户隔离的依据 */
    private String idUser;

    /** 图表名称 */
    private String fileName;

    /** 图表内容（draw.io mxfile XML 全文） */
    private String fileContent;

    /** 创建时间，文本列 yyyy-MM-dd HH:mm:ss */
    private String createTime;

    /** 最后保存时间，文本列 yyyy-MM-dd HH:mm:ss */
    private String updateTime;

    public String getIdFile() {
        return idFile;
    }

    public void setIdFile(String idFile) {
        this.idFile = idFile;
    }

    public String getIdUser() {
        return idUser;
    }

    public void setIdUser(String idUser) {
        this.idUser = idUser;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getFileContent() {
        return fileContent;
    }

    public void setFileContent(String fileContent) {
        this.fileContent = fileContent;
    }

    public String getCreateTime() {
        return createTime;
    }

    public void setCreateTime(String createTime) {
        this.createTime = createTime;
    }

    public String getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(String updateTime) {
        this.updateTime = updateTime;
    }
}
