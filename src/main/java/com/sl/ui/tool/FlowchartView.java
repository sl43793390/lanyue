package com.sl.ui.tool;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.sl.entity.DrawioFileEntity;
import com.sl.mapper.DrawioFileMapper;
import com.sl.security.CurrentUser;
import com.sl.ui.component.Dialogs;
import com.sl.ui.component.DrawioEmbed;
import com.sl.ui.component.UiFactory;
import com.sl.ui.component.ViewBase;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.spring.annotation.SpringComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * drawio绘图（其他工具 → drawio绘图）：内嵌 draw.io 编辑器 + 个人图表库。
 * <p>
 * 工具条是当前登录用户的图表下拉框（{@link ComboBox}），下方整块区域都是内嵌的
 * drawio 编辑器（{@link DrawioEmbed}）——旧版左侧的图表表格已移除，编辑器因此
 * 拿到全部宽度。保存链路：drawio 编辑器内点保存 / Ctrl+S 触发 {@code save}
 * 事件回传 mxfile XML → 这里 upsert 进 {@code drawio_file} 表。图表按
 * {@code id_user}（登录名）隔离，看不到也不碰别人的图。
 * <p>
 * 未保存检测：load 消息带 {@code autosave=1}（见 {@link DrawioEmbed#loadXml}），
 * 用户每画一笔 drawio 都回发 {@code autosave} 事件 → 置 {@code dirty} 标记；
 * {@code save} 事件落库后清除。切换下拉框 / 新建图表时若有未保存修改，弹
 * 三按钮确认框（保存并切换 / 放弃修改并切换 / 取消），防止改动被静默丢弃。
 * <p>
 * 首次新建用空图（一张 A4 空白页）载入编辑器；内容为空的历史图表打开时
 * 同样给空图。数据量小（个人图表库），每次全量查库刷下拉框，不做分页。
 */
@Service
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class FlowchartView extends ViewBase {

    private static final long serialVersionUID = 1L;

    private static final Logger log = LoggerFactory.getLogger(FlowchartView.class);

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final DateTimeFormatter CLOCK_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    /**
     * 空白图：一张默认 A4 竖版空白页。embed 模式没有"新建"入口，
     * 空图表打开时就载入这张底稿。
     */
    private static final String BLANK_XML = "<mxfile host=\"app.diagrams.net\">"
            + "<diagram id=\"page-1\" name=\"页面-1\">"
            + "<mxGraphModel dx=\"1024\" dy=\"768\" grid=\"1\" gridSize=\"10\" guides=\"1\" "
            + "tooltips=\"1\" connect=\"1\" arrows=\"1\" fold=\"1\" page=\"1\" pageScale=\"1\" "
            + "pageWidth=\"827\" pageHeight=\"1169\" math=\"0\" shadow=\"0\">"
            + "<root><mxCell id=\"0\"/><mxCell id=\"1\" parent=\"0\"/></root>"
            + "</mxGraphModel></diagram></mxfile>";

    private final transient DrawioFileMapper mapper;

    private final ComboBox<DrawioFileEntity> fileCombo = new ComboBox<>();
    private final DrawioEmbed embed = new DrawioEmbed();
    private final Div editorWrap = new Div();
    private final Div placeholder = new Div();
    private final Span statusLabel = new Span();
    /** 当前图表的更新时间，显示在删除按钮右侧 */
    private final Span updateTimeLabel = new Span();

    /** 当前在编辑器里打开的图表；null 表示编辑器还没载入任何图 */
    private transient DrawioFileEntity current;

    /** 编辑器里是否有未落库的改动（autosave 事件置位，save 事件落库后清除） */
    private transient boolean dirty = false;

    /** 最近一次 autosave 事件带回的 XML，「保存并切换」时直接拿它落库 */
    private transient String latestXml;

    /** 程序性设置下拉框选中项（reload/open）时置位，不触发切换确认逻辑 */
    private transient boolean syncCombo = false;

    /** 下拉框当前的数据集；回退选中项时按 idFile 从这里找回实例 */
    private transient List<DrawioFileEntity> files = List.of();

    public FlowchartView(DrawioFileMapper mapper) {
        this.mapper = mapper;
        // ------------------------------------------------------------------
        // 工具条：下拉框 + 新建 + 删除 + 更新时间
        // ------------------------------------------------------------------
        fileCombo.setItemLabelGenerator(DrawioFileEntity::getFileName);
        fileCombo.setPlaceholder("选择图表打开");
        fileCombo.setClearButtonVisible(false);
        fileCombo.setWidth("240px");
        fileCombo.addValueChangeListener(e -> onComboChanged(e.getValue()));
        Button newBtn = UiFactory.primary("新建图表", this::createNew);
        Button delBtn = UiFactory.danger("删除", this::deleteSelected);
        updateTimeLabel.getStyle().set("color", "var(--lumo-secondary-text-color)")
                .set("font-size", "var(--lumo-font-size-s)");
        HorizontalLayout bar = UiFactory.group(
                new Span("我的图表"), fileCombo, newBtn, delBtn, updateTimeLabel, spacer(), statusLabel);
        add(bar);

        // ------------------------------------------------------------------
        // 编辑器 / 占位提示（独占下拉框以下全部空间）
        // ------------------------------------------------------------------
        placeholder.add(UiFactory.emptyHint("从上方下拉框选择图表打开，或点「新建图表」开始绘制"));
        placeholder.setSizeFull();
        placeholder.getStyle().set("display", "flex")
                .set("align-items", "center")
                .set("justify-content", "center");
        // 占位符与编辑器都常驻在容器里，用显隐切换——embed 绝不能拆下再装回：
        // 跨请求的 detach 会让 Vaadin 重建宿主 DOM 元素，消息桥（挂在元素上的
        // __drawioBridge 标记和闭包里捕获的 $server/iframe 引用）随之全部失效，
        // drawio 重启后的 init 无人应答，表现为绘图区永远转圈（2026-09-27 修的
        // 「删除当前图表后切回另一张卡加载中」就是这个）。常驻还顺带消灭了
        // 每次切换图都重载 iframe 的十几秒等待。
        editorWrap.add(placeholder, embed);
        embed.setVisible(false);
        editorWrap.setHeightFull();
        editorWrap.setWidthFull();
        editorWrap.getStyle().set("flex", "1 1 auto").set("min-width", "0");
        add(editorWrap);
        setFlexGrow(1, editorWrap);

        // 编辑器回调：save 落库、autosave 只做未保存标记
        embed.addSaveListener(this::onSaved);
        embed.addAutosaveListener(this::onAutosave);

        reload();
    }

    // ------------------------------------------------------------------
    // 数据
    // ------------------------------------------------------------------

    private void reload() {
        QueryWrapper<DrawioFileEntity> wrapper = new QueryWrapper<>();
        wrapper.eq("id_user", CurrentUser.idOrSystemUser());
        wrapper.orderByDesc("update_time");
        files = mapper.selectList(wrapper);
        // 刷新下拉框并保持选中项：current 换成新查出的实例，
        // 实体引用与下拉框数据集保持一致（实体是可变的，别留旧引用）
        syncCombo = true;
        fileCombo.setItems(files);
        if (current != null) {
            DrawioFileEntity fresh = findById(current.getIdFile());
            if (fresh != null) {
                current = fresh;
                fileCombo.setValue(fresh);
            } else {
                current = null;
                fileCombo.setValue(null);
            }
        } else {
            fileCombo.setValue(null);
        }
        syncCombo = false;
        refreshUpdateTime();
        if (current == null) {
            showPlaceholder();
        }
    }

    private DrawioFileEntity findById(String idFile) {
        for (DrawioFileEntity entity : files) {
            if (entity.getIdFile().equals(idFile)) {
                return entity;
            }
        }
        return null;
    }

    private void refreshUpdateTime() {
        updateTimeLabel.setText(current == null ? ""
                : "更新时间：" + StrUtil.nullToEmpty(current.getUpdateTime()));
    }

    // ------------------------------------------------------------------
    // 打开 / 切换
    // ------------------------------------------------------------------

    private void onComboChanged(DrawioFileEntity target) {
        if (syncCombo) {
            return;
        }
        if (target == null) {
            // 理论到不了（clearButtonVisible=false 兜底）：切回当前图表
            syncSelected();
            return;
        }
        if (current != null && target.getIdFile().equals(current.getIdFile())) {
            return;
        }
        openWithDirtyCheck(target);
    }

    /** 有未保存修改时先确认；无修改（或编辑器未打开图）直接切换。 */
    private void openWithDirtyCheck(DrawioFileEntity target) {
        if (current == null || !dirty) {
            open(target);
            return;
        }
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("未保存的修改");
        dialog.setWidth("480px");
        dialog.add(new Span("图表「" + StrUtil.nullToDefault(current.getFileName(), current.getIdFile())
                + "」有未保存的修改，切换后这些修改将丢失。"));
        dialog.getFooter().add(
                Dialogs.cancelButton(dialog::close),
                new Button("放弃修改并切换", ev -> {
                    dialog.close();
                    open(target);
                }),
                Dialogs.primaryButton("保存并切换", () -> {
                    dialog.close();
                    // 落库的是最近一次 autosave 带回的内容，不走 drawio 的 save 事件
                    if (latestXml != null && !persist(latestXml)) {
                        return; // 保存失败：留在原图表，让用户自己处理
                    }
                    open(target);
                }));
        dialog.open();
    }

    /** 在编辑器中打开一张图；编辑器未就绪时由 embed 组件排队，init 后自动载入。 */
    private void open(DrawioFileEntity entity) {
        current = entity;
        dirty = false;
        latestXml = null;
        syncSelected();
        refreshUpdateTime();
        statusLabel.setText("");
        showEditor();
        String xml = StrUtil.isBlank(entity.getFileContent()) ? BLANK_XML : entity.getFileContent();
        embed.loadXml(xml);
    }

    private void syncSelected() {
        syncCombo = true;
        fileCombo.setValue(current);
        syncCombo = false;
    }

    /** 编辑器与占位符都常驻，只切显隐——绝不能 removeAll/add（见构造器内注释）。 */
    private void showEditor() {
        placeholder.setVisible(false);
        embed.setVisible(true);
    }

    private void showPlaceholder() {
        embed.setVisible(false);
        placeholder.setVisible(true);
    }

    // ------------------------------------------------------------------
    // 新建
    // ------------------------------------------------------------------

    private void createNew() {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("新建图表");
        dialog.setWidth("520px");

        TextField nameField = UiFactory.textField("图表名称", "例如：登录流程", "350px");
        dialog.add(nameField);
        dialog.getFooter().add(Dialogs.cancelButton(dialog::close),
                Dialogs.primaryButton("创建", () -> {
                    String name = StrUtil.trim(nameField.getValue());
                    if (StrUtil.isBlank(name)) {
                        Dialogs.warn("图表名称不能为空！");
                        return;
                    }
                    String now = LocalDateTime.now().format(TIME_FMT);
                    DrawioFileEntity entity = new DrawioFileEntity();
                    // 主键 = 当前系统纳秒值，与 script_mgmt 同一策略
                    entity.setIdFile(String.valueOf(System.nanoTime()));
                    entity.setIdUser(CurrentUser.idOrSystemUser());
                    entity.setFileName(name);
                    entity.setCreateTime(now);
                    entity.setUpdateTime(now);

                    try {
                        mapper.insert(entity);
                    } catch (Exception e) {
                        log.error("新建图表失败", e);
                        Dialogs.error("新建失败：" + e.getMessage());
                        return;
                    }
                    dialog.close();
                    reload();
                    // 当前图表若有未保存修改，同样先确认再切过去
                    openWithDirtyCheck(entity);
                    Dialogs.success("已创建，开始绘制吧（编辑器内点保存或 Ctrl+S 保存）");
                }));
        dialog.open();
    }

    // ------------------------------------------------------------------
    // 保存（drawio save / autosave 事件回调）
    // ------------------------------------------------------------------

    private void onSaved(String xml) {
        if (StrUtil.isBlank(xml)) {
            return;
        }
        if (persist(xml)) {
            dirty = false;
            latestXml = null;
            statusLabel.setText("已保存 " + LocalDateTime.now().format(CLOCK_FMT));
        }
    }

    private void onAutosave(String xml) {
        if (StrUtil.isBlank(xml)) {
            return;
        }
        dirty = true;
        latestXml = xml;
        statusLabel.setText("有未保存的修改 " + LocalDateTime.now().format(CLOCK_FMT));
    }

    /**
     * 把一份 XML 落库到当前图表（{@code current} 为 null 时兜底落成"未命名图表"）。
     * 保存成功后刷新下拉框与更新时间。{@code current} 不变——切换走
     * {@link #openWithDirtyCheck}，这里只写库。
     *
     * @return 是否保存成功
     */
    private boolean persist(String xml) {
        String now = LocalDateTime.now().format(TIME_FMT);
        try {
            if (current == null) {
                // 极端情况：编辑器就绪前没选过文件但用户直接画了图并保存，兜底落成"未命名图表"
                DrawioFileEntity entity = new DrawioFileEntity();
                entity.setIdFile(String.valueOf(System.nanoTime()));
                entity.setIdUser(CurrentUser.idOrSystemUser());
                entity.setFileName("未命名图表");
                entity.setCreateTime(now);
                entity.setFileContent(xml);
                entity.setUpdateTime(now);
                mapper.insert(entity);
                current = entity;
            } else {
                // 只更新内容与时间：用部分实体走 updateById 的非空字段策略
                DrawioFileEntity upd = new DrawioFileEntity();
                upd.setIdFile(current.getIdFile());
                upd.setFileContent(xml);
                upd.setUpdateTime(now);
                mapper.updateById(upd);
                current.setFileContent(xml);
                current.setUpdateTime(now);
            }
        } catch (Exception e) {
            log.error("保存图表失败：{}", current == null ? "-" : current.getFileName(), e);
            Dialogs.error("保存失败：" + e.getMessage());
            return false;
        }
        reload();
        return true;
    }

    // ------------------------------------------------------------------
    // 删除
    // ------------------------------------------------------------------

    private void deleteSelected() {
        DrawioFileEntity sel = fileCombo.getValue();
        if (sel == null) {
            Dialogs.warn("请先在上方下拉框选择一个图表");
            return;
        }
        Dialogs.confirmDanger("删除图表",
                "确认删除图表「" + StrUtil.nullToDefault(sel.getFileName(), sel.getIdFile()) + "」吗？删除后不可恢复。",
                "确认删除", () -> {
                    try {
                        mapper.deleteById(sel.getIdFile());
                    } catch (Exception e) {
                        log.error("删除图表失败：{}", sel.getIdFile(), e);
                        Dialogs.error("删除失败：" + e.getMessage());
                        return;
                    }
                    if (current != null && sel.getIdFile().equals(current.getIdFile())) {
                        current = null;
                        dirty = false;
                        latestXml = null;
                        statusLabel.setText("");
                        showPlaceholder();
                    }
                    reload();
                    Dialogs.success("已删除");
                });
    }
}
