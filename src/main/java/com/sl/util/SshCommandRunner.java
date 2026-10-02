package com.sl.util;

import cn.hutool.core.util.StrUtil;
import com.sl.entity.ConnectionInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.io.Serializable;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 「在目标机上跑命令」的统一通道：包住 {@link SSHClientUtil}，并负责一件事——
 * <b>连接没了自动重建</b>。
 * <p>
 * <b>为什么必须有这一层。</b>连接走共享池 {@link SshConnectionPool}，池里的连接
 * 空闲超过 {@link SshConnectionPool#IDLE_KEEP_MS}（15 分钟）会被回收线程
 * {@code closeConnection()} 掉。以前的写法是页面在构造/连接时
 * {@code acquire()} 一次、把 {@link SSHClientUtil} 存进字段长期持有，
 * 用户去泡个茶回来点「刷新」，命令发给了一条已经关掉的连接，界面直接弹
 * 「SSH 连接未建立，无法执行命令」，而且这条连接永远不会再活过来——
 * 除非用户手动重新点一次「连接」。这个 bug 在 compose 管理页上稳定复现
 * （2026-10-02 反馈：页面开着超过 15 分钟，点刷新就报错）。
 * <p>
 * 现在的规则是：<b>每次发命令前确认连接还活着，死了就从池里要一条新的</b>，
 * 池子自己会把失效条目丢掉重建（见 {@link SshConnectionPool#acquire}）。
 * 调用方不需要关心连接的生命周期，也不需要重新点「连接」。
 * <p>
 * <b>重试策略是保守的。</b>执行过程中抛异常时，只有确认「连接确实已经不在」
 * （{@link #isAlive()} 为 false）才重连重试一次；连接还在却报错，说明是命令本身
 * 的问题（退出码非 0、权限不足等），原样抛出，绝不对一条可能已经部分执行过的
 * 命令再补一刀。
 * <p>
 * <b>线程安全。</b>同一条连接上并发建 channel 的时序不好保证，所有命令与重连
 * 都在同一把锁里串行执行（命令都是毫秒级，串行不是瓶颈）。
 */
public class SshCommandRunner implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(SshCommandRunner.class);

    /**
     * 退出码回传标记。
     * <p>
     * sshj 的 {@code Session.Command} 拿不到稳定的退出码（{@code getExitStatus()}
     * 在通道关闭后常为 null），所以在命令末尾用 {@code printf} 把 {@code $?} 打出来，
     * 由调用方按标记切分。该前缀正常输出里不可能出现。
     */
    public static final String EXIT_MARK = "__SSH_EXIT_CODE__:";

    private final ConnectionInfo info;

    /** 命令与重连的串行锁 */
    private final Object lock = new Object();

    /** 当前持有的连接；失效时会被换成池里新的一条，所以是 volatile */
    private volatile SSHClientUtil ssh;

    private final AtomicInteger reconnects = new AtomicInteger();

    private volatile boolean closed;

    public SshCommandRunner(ConnectionInfo info) throws IOException {
        if (info == null) {
            throw new IOException("连接信息为空");
        }
        this.info = info;
        this.ssh = SshConnectionPool.acquire(info);
    }

    public ConnectionInfo getInfo() {
        return info;
    }

    public String hostLabel() {
        return info.getIdHost() + (StrUtil.isBlank(info.getCdPort()) ? "" : ":" + info.getCdPort());
    }

    /**
     * 当前连接是否还能用。只做本地判断，不发命令、不改状态。
     * <p>
     * sshj 的 {@code isConnected()} 反映传输层是否还在跑：服务端掐线、
     * 网络断开、池子回收后都会变成 false，因此可以拿它当作「要不要重连」的依据。
     */
    public boolean isAlive() {
        try {
            SSHClientUtil current = ssh;
            return current != null && current.isConnected();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 确保手里有一条活着的连接，死了就从共享池重新取。
     *
     * @return true 表示本次确实重建了连接（界面可据此给一次「已自动重连」的提示）
     */
    public boolean ensureConnection() throws IOException {
        ensureOpen();
        if (isAlive()) {
            return false;
        }
        synchronized (lock) {
            if (isAlive()) {
                return false;
            }
            ssh = SshConnectionPool.acquire(info);
            reconnects.incrementAndGet();
            log.info("{} 的共享 SSH 连接已失效（空闲回收或服务端断开），已自动重连", hostLabel());
            return true;
        }
    }

    /**
     * 取底层连接（SFTP 等需要自开通道的场景用），取之前先确保它有活气。
     */
    public SSHClientUtil ssh() throws IOException {
        ensureConnection();
        return ssh;
    }

    /**
     * 取底层连接，但不因为重连失败而抛异常（重连失败会返回旧的失效实例，
     * 调用方随后的操作自然会报出真实原因）。给签名不能改的老调用点用。
     */
    public SSHClientUtil currentSsh() {
        try {
            ensureConnection();
        } catch (IOException e) {
            log.warn("{} 自动重连失败：{}", hostLabel(), e.getMessage());
        }
        return ssh;
    }

    /**
     * 执行一条命令，返回「退出码 + 输出（stdout 与 stderr 合并）」。
     * <p>
     * 命令末尾统一补 {@code 2>&1}：sshj 把 stdout / stderr 分成两条流，
     * 只看 stdout 会把「command not found」「permission denied」这类真实原因全丢掉。
     */
    public Result exec(String command) throws IOException {
        ensureOpen();
        if (StrUtil.isBlank(command)) {
            throw new IOException("命令为空");
        }
        String wrapped = command + " 2>&1; printf '\\n" + EXIT_MARK + "%s\\n' \"$?\"";
        String raw;
        synchronized (lock) {
            ensureConnection();
            try {
                raw = ssh.executeCommand(wrapped);
            } catch (RuntimeException | IOException first) {
                // 连接还在却报错 = 命令自身的问题（或通道异常），原样抛出，不重试，
                // 避免对一条可能已经执行到一半的命令再来一次
                if (isAlive()) {
                    throw asIOException(first);
                }
                // 连接已经没了（刚好在 ensureConnection 之后被回收/断开）：重连一次再试
                ssh = SshConnectionPool.acquire(info);
                reconnects.incrementAndGet();
                log.warn("{} 上执行命令时连接中断（{}），已重连并重试一次", hostLabel(), first.getMessage());
                raw = ssh.executeCommand(wrapped);
            }
        }
        return Result.parse(raw);
    }

    /** 执行命令，非 0 退出码直接抛 IOException，消息里带上真实输出 */
    public String execChecked(String command) throws IOException {
        Result result = exec(command);
        if (!result.isOk()) {
            throw new IOException(result.errorMessage());
        }
        return result.getOutput();
    }

    /** 打开一段命令的输出流（长输出、二进制流用），调用方负责关闭 */
    public java.io.InputStream openStream(String command) throws IOException {
        ensureConnection();
        return ssh.openCommandStream(command);
    }

    /**
     * 取走「自上次询问以来是否发生过自动重连」，并把计数清零。
     * 界面在成功回调里调它，弹一次「连接已自动重连」的提示即可，不会重复弹。
     */
    public boolean consumeReconnectNotice() {
        return reconnects.getAndSet(0) > 0;
    }

    /** 用户主动断开：只做标记，共享连接归池子管，不动连接本身 */
    @Override
    public void close() {
        closed = true;
    }

    /**
     * 解析探测脚本输出的 {@code KEY=VALUE} 行。
     * <p>
     * 「一条命令把环境信息全捞回来」是这套页面里通用的省往返手法：脚本 echo 一批
     * KEY=VALUE，本地按行切开。放这里是为了让 docker 探测与 Linux 环境探测共用同一份
     * 解析（两边的容错口径必须一致：值里带 {@code =} 只按第一个切、空值保留空串）。
     */
    public static java.util.Map<String, String> parseKeyValues(String output) {
        java.util.Map<String, String> map = new java.util.HashMap<>();
        if (output == null) {
            return map;
        }
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            int idx = trimmed.indexOf('=');
            if (idx > 0) {
                map.put(trimmed.substring(0, idx).trim(), trimmed.substring(idx + 1).trim());
            }
        }
        return map;
    }

    public boolean isClosed() {
        return closed;
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("连接已关闭，请重新连接目标服务器");
        }
    }

    private static IOException asIOException(Exception e) {
        return e instanceof IOException io ? io : new IOException(StrUtil.emptyToDefault(e.getMessage(), e.getClass().getSimpleName()), e);
    }

    /**
     * 一条命令的执行结果。
     */
    public static class Result implements Serializable {

        private static final long serialVersionUID = 1L;

        private final int exitCode;
        private final String output;

        protected Result(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output == null ? "" : output;
        }

        /** 按退出码标记切分原始输出；拿不到标记（连接中断等）按失败处理但保留输出 */
        public static Result parse(String raw) {
            if (raw == null) {
                return new Result(-1, "");
            }
            int idx = raw.lastIndexOf(EXIT_MARK);
            if (idx < 0) {
                return new Result(-1, raw);
            }
            String output = raw.substring(0, idx);
            if (output.endsWith("\n")) {
                output = output.substring(0, output.length() - 1);
            }
            if (output.endsWith("\r")) {
                output = output.substring(0, output.length() - 1);
            }
            String codeText = raw.substring(idx + EXIT_MARK.length()).trim();
            int code;
            try {
                code = Integer.parseInt(codeText.isEmpty() ? "-1" : codeText);
            } catch (NumberFormatException e) {
                code = -1;
            }
            return new Result(code, output);
        }

        public int getExitCode() {
            return exitCode;
        }

        public String getOutput() {
            return output;
        }

        public boolean isOk() {
            return exitCode == 0;
        }

        /** 失败时给用户看的简短原因：优先取输出的最后几行 */
        public String errorMessage() {
            String text = StrUtil.trimToEmpty(output);
            if (text.isEmpty()) {
                return "退出码 " + exitCode + "（命令没有任何输出）";
            }
            String[] lines = text.split("\\R");
            StringBuilder sb = new StringBuilder();
            int from = Math.max(0, lines.length - 3);
            for (int i = from; i < lines.length; i++) {
                if (!sb.isEmpty()) {
                    sb.append(" / ");
                }
                sb.append(lines[i].trim());
            }
            return sb.toString();
        }
    }
}
