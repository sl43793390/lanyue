package com.sl.docker;

import com.sl.docker.model.DockerDaemonStatus;

import java.io.IOException;

/**
 * docker daemon 不可用时抛出的异常。
 * <p>
 * 单独定义一个类型（而不是复用 {@link IOException}）是为了让上层能区分
 * 「命令真的失败了」和「压根不该发这条命令」：
 * <ul>
 *   <li>{@link com.sl.component.docker.DockerUi} 对它<b>不再打印整段堆栈、也不再弹红色错误</b>
 *       ——日志里那种同一个错误刷屏就是这里来的；</li>
 *   <li>{@link com.sl.component.docker.AbstractDockerPage} 收到它就<b>不再自动重试</b>，
 *       而是弹出「是否启动 Docker 服务」，避免 reload→失败→reload 的死循环；</li>
 *   <li>带上 {@link DockerDaemonStatus}，弹窗里可以直接把"为什么连不上"和"将要执行的命令"讲清楚。</li>
 * </ul>
 */
public class DockerDaemonDownException extends IOException {

    private static final long serialVersionUID = 1L;

    private final DockerDaemonStatus status;

    public DockerDaemonDownException(DockerDaemonStatus status) {
        super(buildMessage(status));
        this.status = status;
    }

    public DockerDaemonStatus getStatus() {
        return status;
    }

    private static String buildMessage(DockerDaemonStatus status) {
        if (null == status) {
            return "docker 服务不可用，已停止继续发送 docker 命令";
        }
        if (!status.isDockerInstalled()) {
            return "目标机上没有 docker 命令，已停止继续发送 docker 命令";
        }
        if (status.isDaemonProcess()) {
            return "dockerd 进程在但客户端连不上 daemon（" + status.getDaemonError()
                    + "），已停止继续发送 docker 命令";
        }
        return "docker 服务未运行（" + status.getDaemonError() + "），已停止继续发送 docker 命令";
    }
}
