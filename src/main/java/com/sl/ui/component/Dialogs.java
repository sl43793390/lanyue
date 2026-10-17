package com.sl.ui.component;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;

/**
 * 提示与确认对话框。
 * <p>
 * 旧项目对应 {@code ConfirmationDialogPopupWindow} 等一整套自定义 Window
 * （还有配套的 {@code ConfirmationEvent} / {@code ConfirmationEventListener} 观察者接口）。
 * Vaadin 24 已经内置 {@link ConfirmDialog}，把回调直接写成 lambda 即可，
 * 那套事件总线没必要再搬。
 * <p>
 * {@link Notification} 在这里统一了位置与时长：旧代码里 {@code Notification.show(msg)}
 * 的默认位置在不同版本间变过，与其让各页面各写各的，不如收在一处。
 */
public final class Dialogs {

    /** 提示停留时长，毫秒 */
    private static final int DURATION = 2500;

    private Dialogs() {
    }

    // ------------------------------------------------------------------
    // 轻提示
    // ------------------------------------------------------------------

    public static void info(String message) {
        show(message, NotificationVariant.LUMO_CONTRAST);
    }

    public static void success(String message) {
        show(message, NotificationVariant.LUMO_SUCCESS);
    }

    /** 参数校验不通过、操作前置条件不满足一类。 */
    public static void warn(String message) {
        show(message, NotificationVariant.LUMO_WARNING);
    }

    /** 操作失败。 */
    public static void error(String message) {
        show(message, NotificationVariant.LUMO_ERROR);
    }

    private static void show(String message, NotificationVariant variant) {
        Notification notification = new Notification(message, DURATION, Notification.Position.BOTTOM_END);
        notification.addThemeVariants(variant);
        notification.open();
    }

    // ------------------------------------------------------------------
    // 确认
    // ------------------------------------------------------------------

    /**
     * 二次确认。点「确定」才执行 {@code onConfirm}，取消则什么都不做。
     */
    public static void confirm(String header, String message, Runnable onConfirm) {
        buildConfirm(header, message, "确定", null, onConfirm);
    }

    /**
     * 危险操作确认：确认按钮标红。删除、覆盖、重启容器这类用它。
     *
     * @param confirmText 确认按钮文案，用动词更清楚，例如「删除」「重启」
     */
    public static void confirmDanger(String header, String message, String confirmText, Runnable onConfirm) {
        buildConfirm(header, message, confirmText, "error primary", onConfirm);
    }

    private static void buildConfirm(String header, String message, String confirmText,
                                     String confirmTheme, Runnable onConfirm) {
        ConfirmDialog dialog = new ConfirmDialog();
        dialog.setHeader(header);
        dialog.setText(message);
        dialog.setConfirmText(confirmText);
        dialog.setCancelText("取消");
        if (confirmTheme != null) {
            dialog.setConfirmButtonTheme(confirmTheme);
        }
        dialog.setCancelable(true);
        dialog.addConfirmListener(event -> {
            if (onConfirm != null) {
                onConfirm.run();
            }
        });
        dialog.open();
    }

    /**
     * 单按钮提示弹窗，只有一个「确定」。
     * <p>
     * 「JSON 格式校验失败」这类提示只需要用户看一眼点掉、不需要做选择，
     * 而 {@link ConfirmDialog} 的取消按钮关不掉（只能置灰不可点的外框，按钮还在），
     * 所以这里用 {@link Dialog} 自己摆一个。
     */
    public static void alert(String header, String message) {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle(header);
        dialog.setModal(true);
        dialog.setDraggable(false);
        dialog.setResizable(false);
        dialog.setWidth("360px");

        Span text = new Span(message);
        text.getStyle().set("white-space", "pre-wrap");
        dialog.add(text);
        dialog.getFooter().add(dialogActions(primaryButton("确定", dialog::close)));
        dialog.open();
    }

    // ------------------------------------------------------------------
    // 对话框内容排版
    // ------------------------------------------------------------------

    /**
     * 对话框底部的按钮行（右对齐）。
     * <p>
     * {@code ConfirmDialog} 自带按钮区，但「新建/编辑」这类自定义表单对话框要自己摆。
     */
    public static HorizontalLayout dialogActions(Component... components) {
        HorizontalLayout actions = new HorizontalLayout(components);
        actions.setWidthFull();
        actions.setJustifyContentMode(FlexComponent.JustifyContentMode.END);
        actions.getStyle().set("padding-top", "8px");
        return actions;
    }

    /** 对话框「确定」按钮，主色。 */
    public static Button primaryButton(String text, Runnable action) {
        Button button = new Button(text, event -> action.run());
        button.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        return button;
    }

    /** 对话框「取消」按钮。 */
    public static Button cancelButton(Runnable action) {
        Button button = new Button("取消", event -> action.run());
        return button;
    }
}
