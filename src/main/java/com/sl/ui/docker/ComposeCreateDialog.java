package com.sl.ui.docker;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.sl.docker.ComposeService;
import com.sl.docker.ComposeTemplates;
import com.sl.docker.model.ComposeProject;
import com.sl.docker.model.ComposeTemplate;
import com.sl.ui.component.Dialogs;
import com.sl.ui.component.UiFactory;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.upload.Upload;
import com.vaadin.flow.server.streams.UploadHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 新建 Compose 项目弹窗。
 * <p>
 * 对应旧项目的 {@code ComposeProjectCreateWindow}（约 800 行的 TabSheet 版），
 * 这里收成单页弹窗：起手方式（模板库 / 空白骨架 / 上传本地 yml）→
 * 编辑 compose 文件与 .env → 创建时一次写到目标机项目目录。
 * 与旧实现的差别：
 * <ul>
 *   <li>校验预览（临时目录跑 {@code docker compose config}）和 Git 拉取是低频操作，
 *       本轮未迁，创建后可直接在项目「配置」里改文件、用命令行同步；</li>
 *   <li>模板库从旧项目原样移植（{@link ComposeTemplates}，9 套常用服务模板）。</li>
 * </ul>
 */
class ComposeCreateDialog extends Dialog {

    private static final Logger log = LoggerFactory.getLogger(ComposeCreateDialog.class);

    private static final String SOURCE_TEMPLATE = "从模板库生成";
    private static final String SOURCE_BLANK = "空白骨架";
    private static final String SOURCE_UPLOAD = "上传本地 yml";
    private static final String ADD_FILE = "＋ 新增文件…";

    private final ComposeService service;
    private final String baseDir;
    private final Consumer<ComposeProject> onCreated;

    private final TextField nameField = UiFactory.textField("项目名", "如 my-app");
    private final TextField descField = UiFactory.textField("项目描述", "可选，列表里能一眼认出来");
    private final ComboBox<String> sourceCombo = new ComboBox<>("起手方式");
    private final ComboBox<String> templateCombo = new ComboBox<>("模板");
    private final ComboBox<String> fileCombo = new ComboBox<>("文件");
    private final TextArea editor = UiFactory.textArea("内容");
    private final TextArea envArea = UiFactory.textArea(".env 变量");

    /** 各文件的草稿：切文件前先落回这里，避免服务端没收到最后一次输入 */
    private final Map<String, String> drafts = new LinkedHashMap<>();

    ComposeCreateDialog(ComposeService service, String baseDir, Consumer<ComposeProject> onCreated) {
        this.service = service;
        this.baseDir = StrUtil.emptyToDefault(baseDir, "/opt/" + ComposeService.DEFAULT_BASE_DIR_NAME);
        this.onCreated = onCreated;

        setHeaderTitle("新建 Compose 项目");
        // 弹窗尺寸带视口上限：小窗口下不顶出屏幕（920/760 是大屏下的理想值）
        setWidth("min(920px, 94vw)");
        setHeight("min(800px, 92vh)");
       setResizable(true);
        /*
         * 宽度都要给 label 左置的全局规则（padding-left: 118px）留出份：
         * 260px 的下拉框实际输入区只剩 140px 左右，「从模板库生成」根本显示不下。
         * 其余需要铺满整行的字段/行不写 width:100%——字段 host 带着这层 padding，
         * content-box 下 100% 会溢出弹窗右侧；宽度交给 body 的 align-items: STRETCH。
         */
        nameField.setWidth("320px");
        nameField.setAllowedCharPattern("[A-Za-z0-9_-]");

        // 起手方式行
        sourceCombo.setItems(SOURCE_TEMPLATE, SOURCE_BLANK, SOURCE_UPLOAD);
        sourceCombo.setValue(SOURCE_TEMPLATE);
        sourceCombo.setWidth("320px");
        sourceCombo.setAllowCustomValue(false);
        sourceCombo.addValueChangeListener(e -> applySource(e.getValue()));

        templateCombo.setWidth("360px");
        templateCombo.setAllowCustomValue(false);
        templateCombo.setItems(ComposeTemplates.all().stream().map(ComposeTemplate::getName).toList());
        templateCombo.setValue(ComposeTemplates.all().get(0).getName());
        templateCombo.addValueChangeListener(e -> {
            ComposeTemplate t = ComposeTemplates.byName(e.getValue());
            if (t != null) {
                loadFiles(Map.of(ComposeService.DEFAULT_FILE, t.getYaml()), t.getEnv());
            }
        });

        Span pathHint = new Span("项目目录：" + baseDir + "/<项目名>，项目名同时是目录名与 compose 项目名");
        pathHint.getStyle().set("font-size", "var(--lumo-font-size-xs)");
        pathHint.getStyle().set("color", "var(--lumo-secondary-text-color)");
        pathHint.getStyle().set("min-width", "0");

        HorizontalLayout sourceRow = new HorizontalLayout(sourceCombo, templateCombo, pathHint);
        sourceRow.setAlignItems(FlexComponent.Alignment.CENTER);
        sourceRow.setFlexGrow(1, pathHint);

        // 文件编辑区
        fileCombo.setWidth("360px");
        fileCombo.setAllowCustomValue(true);
        fileCombo.setItems(ADD_FILE);
        fileCombo.addValueChangeListener(e -> {
            String name = e.getValue();
            if (name == null || name.isBlank()) {
                return;
            }
            // 不认识的项（含「＋ 新增文件…」）按空内容处理，等于清空编辑器起名
            String content = drafts.get(name);
            editor.setValue(content == null ? "" : content);
        });
        // 自定义输入 = 新文件名
        fileCombo.addCustomValueSetListener(e -> addNewFile(e.getDetail()));

        /*
         * 编辑器只靠 flex-grow 撑高度：height:100% 在 flex 纵向容器里不扣兄弟行的高度，
         * 会把 .env 一行顶出弹窗可视区（LogDetailView 里同一个坑）。
         */
        editor.getElement().getStyle().set("font-family", "var(--lumo-font-family-monospace, monospace)");
        editor.getElement().getStyle().set("font-size", "var(--lumo-font-size-xs)");
        editor.addValueChangeListener(e -> {
            String name = fileCombo.getValue();
            if (name != null && !name.isBlank() && !ADD_FILE.equals(name)) {
                drafts.put(name, e.getValue());
            }
        });

        Button removeFileBtn = UiFactory.button("删除当前文件", this::removeCurrentFile);
        HorizontalLayout fileRow = new HorizontalLayout(fileCombo, removeFileBtn);
        fileRow.setAlignItems(FlexComponent.Alignment.CENTER);

        envArea.setHeight("120px");
        envArea.getElement().getStyle().set("font-family", "var(--lumo-font-family-monospace, monospace)");
        envArea.getElement().getStyle().set("font-size", "var(--lumo-font-size-xs)");

        VerticalLayout body = new VerticalLayout(nameField, descField, sourceRow, fileRow, editor, envArea);
        body.setSizeFull();
        body.setPadding(false);
        // FlexLayout 默认 align-items 是 flex-start（不是 stretch），不显式声明的话
        // 整个弹窗内容按各自内容宽收缩、缩在左边；stretch 还顺带解决了
        // 「铺满整行不写 width:100%」的溢出问题（见构造器开头的注释）。
        body.setDefaultHorizontalComponentAlignment(FlexComponent.Alignment.STRETCH);
        body.setFlexGrow(1, editor);
        add(body);

        Button cancel = UiFactory.button("取消", this::close);
        Button create = UiFactory.primary("创建项目", this::doCreate);
        getFooter().add(cancel, create);

        // 默认载入第一个模板
        ComposeTemplate first = ComposeTemplates.all().get(0);
        loadFiles(Map.of(ComposeService.DEFAULT_FILE, first.getYaml()), first.getEnv());
    }

    // ------------------------------------------------------------------
    // 起手方式
    // ------------------------------------------------------------------

    private void applySource(String source) {
        boolean templateMode = SOURCE_TEMPLATE.equals(source);
        templateCombo.setVisible(templateMode);
        if (SOURCE_BLANK.equals(source)) {
            loadFiles(Map.of(ComposeService.DEFAULT_FILE, ComposeTemplates.BLANK), "");
        } else if (SOURCE_UPLOAD.equals(source)) {
            openUploadDialog();
        }
    }

    /** 上传本地 yml：读进草稿当作一个文件参与创建（可多传）。 */
    private void openUploadDialog() {
        Dialog uploadDialog = new Dialog();
        uploadDialog.setHeaderTitle("上传本地 yml 文件");
        Upload upload = new Upload(UploadHandler.toFile(
                (metadata, file) -> getUI().ifPresent(ui -> ui.access(() -> {
                    String fileName = metadata.fileName();
                    try {
                        String safe = UiFactory.safeFileName(fileName);
                        String content = FileUtil.readString(file, StandardCharsets.UTF_8);
                        file.delete();
                        drafts.put(safe, content);
                        refreshFileCombo(safe);
                        Dialogs.success("已载入 " + safe + "，可继续编辑后创建");
                    } catch (Exception e) {
                        log.warn("读取上传文件失败：{}", e.getMessage());
                        Dialogs.error("读取上传文件失败：" + StrUtil.emptyToDefault(e.getMessage(), "未知错误"));
                    }
                    uploadDialog.close();
                })),
                metadata -> {
                    try {
                        return File.createTempFile("lanyue-compose-", "-" + UiFactory.safeFileName(metadata.fileName()));
                    } catch (IOException e) {
                        throw new IllegalStateException(e.getMessage(), e);
                    }
                }));
        upload.setI18n(UiFactory.UPLOAD_I18N);
        upload.setDropAllowed(false);
        upload.setMaxFiles(1);
        upload.addAllFinishedListener(event -> upload.clearFileList());
        upload.addFileRejectedListener(event ->
                Dialogs.warn("上传被拒绝：" + StrUtil.emptyToDefault(event.getErrorMessage(), "不满足上传限制")));
        uploadDialog.add(upload);
        uploadDialog.getFooter().add(Dialogs.cancelButton(uploadDialog::close));
        uploadDialog.open();
    }

    // ------------------------------------------------------------------
    // 文件草稿
    // ------------------------------------------------------------------

    private void loadFiles(Map<String, String> files, String env) {
        drafts.clear();
        drafts.putAll(files);
        envArea.setValue(StrUtil.nullToEmpty(env));
        refreshFileCombo(ComposeService.DEFAULT_FILE);
    }

    /**
     * 刷新文件下拉并同步编辑器。
     * <p>
     * 两个坑都在这里堵：
     * <ul>
     *   <li>「＋ 新增文件…」是下拉里的一个固定选项，不在 drafts 里——刷新列表时
     *       必须手动补回去，否则创建过一次新文件后下拉里就再也找不到新增入口；</li>
     *   <li>ComboBox 的 setValue 遇到<b>值没变</b>时不触发 valueChange，编辑器就会
     *       停留在旧文件的内容、而草稿已经是新的（比如切「空白骨架」时
     *       docker-compose.yml 前后两次都是同一个名字）。所以这里统一显式同步编辑器，
     *       不依赖监听器碰运气。</li>
     * </ul>
     */
    private void refreshFileCombo(String select) {
        java.util.List<String> names = new java.util.ArrayList<>(drafts.keySet());
        names.add(ADD_FILE);
        fileCombo.setItems(names);
        if (!java.util.Objects.equals(fileCombo.getValue(), select)) {
            fileCombo.setValue(select);
        }
        // 值相同时 setValue 不发事件，编辑器内容也要跟上草稿
        String content = drafts.get(select);
        editor.setValue(content == null ? "" : content);
    }

    private void addNewFile(String rawName) {
        String name;
        try {
            name = UiFactory.safeFileName(rawName);
        } catch (IOException e) {
            Dialogs.warn("文件名不合法：" + e.getMessage());
            return;
        }
        if (name.isBlank()) {
            return;
        }
        if (!drafts.containsKey(name)) {
            drafts.put(name, "");
        }
        refreshFileCombo(name);
    }

    private void removeCurrentFile() {
        String name = fileCombo.getValue();
        if (name == null || ADD_FILE.equals(name)) {
            return;
        }
        if (ComposeService.DEFAULT_FILE.equals(name)) {
            Dialogs.warn("docker-compose.yml 是基础文件，不能删除，可以清空后重写");
            return;
        }
        Dialogs.confirm("删除文件草稿", "确认从本次创建中移除 " + name + " 吗？（不影响已存在的项目）", () -> {
            drafts.remove(name);
            refreshFileCombo(ComposeService.DEFAULT_FILE);
        });
    }

    // ------------------------------------------------------------------
    // 创建
    // ------------------------------------------------------------------

    private void doCreate() {
        String name = StrUtil.trimToEmpty(nameField.getValue());
        try {
            ComposeService.checkName(name);
        } catch (Exception e) {
            Dialogs.warn(e.getMessage());
            return;
        }
        Map<String, String> files = new LinkedHashMap<>();
        drafts.forEach((k, v) -> {
            if (StrUtil.isNotBlank(v)) {
                files.put(k, v);
            }
        });
        if (!files.containsKey(ComposeService.DEFAULT_FILE)) {
            Dialogs.warn("必须包含 " + ComposeService.DEFAULT_FILE + "，否则 compose 找不到默认配置");
            return;
        }
        String env = envArea.getValue();
        setEnabled(false);
        getUI().ifPresent(ui -> new Thread(() -> {
            String failure = null;
            ComposeProject created = null;
            try {
                created = service.createProject(baseDir, name, StrUtil.trimToEmpty(descField.getValue()), files, env);
            } catch (Exception e) {
                failure = e.getMessage();
                log.warn("创建 compose 项目 {} 失败：{}", name, e.getMessage());
            }
            ComposeProject finalProject = created;
            String finalFailure = failure;
            ui.access(() -> {
                setEnabled(true);
                if (finalFailure != null) {
                    Dialogs.error("创建失败：" + StrUtil.emptyToDefault(finalFailure, "未知错误"));
                    return;
                }
                close();
                Dialogs.success("项目 " + finalProject.getName() + " 已创建（" + finalProject.getDirectory() + "）");
                onCreated.accept(finalProject);
            });
        }, "compose-create-" + name).start());
    }
}
