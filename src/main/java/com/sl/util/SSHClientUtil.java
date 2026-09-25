package com.sl.util;

import cn.hutool.core.util.StrUtil;
import com.sl.entity.ConnectionInfo;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.common.IOUtils;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.sftp.FileAttributes;
import net.schmizz.sshj.sftp.RemoteResourceInfo;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import net.schmizz.sshj.userauth.keyprovider.KeyProvider;
import net.schmizz.sshj.xfer.FileSystemFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.util.List;
import java.util.concurrent.TimeUnit;


public class SSHClientUtil {

    private static final Logger log = LoggerFactory.getLogger(SSHClientUtil.class);

    /** 建链超时（毫秒），必须在 connect 之前设置 */
    private static final int CONNECT_TIMEOUT_MS = 15000;
    /** 读写超时（毫秒） */
    private static final int SO_TIMEOUT_MS = 1200000;
    private static final int KEEP_ALIVE_SECONDS = 30;

    private final String ip;
    private final Integer port;
    private final String username;
    private final String password;
    private SSHClient sshClient;
    private SFTPClient sftpClient;

    /**
     * 只保存参数，不建立连接。
     * <p>
     * <strong>刻意只保留这一个构造器。</strong>原来还有一个用于私钥连接的
     * {@code SSHClientUtil(String host, int port, String username, String privateKeyPath)}，
     * 调用方写 {@code new SSHClientUtil(host, Integer.parseInt(port), user, password)} 时，
     * 实参类型是 {@code int}，Java 重载解析在第一阶段（不允许装箱）就会精确命中
     * {@code (String, int, String, String)} 这个<b>私钥</b>版本，把密码当成私钥路径。
     * <p>
     * 而 sshj 的 {@code authPublickey(username, String... locations)} 会把
     * {@code loadKeys(loc)} 抛出的 IOException 静默吞掉、传一个空的 KeyProvider 列表给
     * {@code auth()}，最终只有一句毫无信息量的
     * {@code UserAuthException: Exhausted available authentication methods}，
     * 让人误以为是"服务器不允许认证"。
     * <p>
     * 私钥连接请用 {@link #byPrivateKey(String, int, String, String, String)}
     * 或 {@link #connect(ConnectionInfo)}，从方法名上就无法再和密码连接混淆。
     */
    private SSHClientUtil(String ip, Integer port, String username, String password) {
        this.ip = ip;
        this.username = username;
        this.password = password;
        this.port = (null == port || port <= 0) ? 22 : port;
    }

    /**
     * 密码认证（不连接，需再调 {@link #openConnection()}）
     */
    public static SSHClientUtil byPassword(String ip, int port, String username, String password) {
        return new SSHClientUtil(ip, port, username, password);
    }

    /**
     * 私钥认证，返回时连接已经建立。
     *
     * @param passphrase 私钥口令，没有就传 null/空串
     */
    public static SSHClientUtil byPrivateKey(String ip, int port, String username, String privateKeyPath, String passphrase)
            throws IOException {
        SSHClientUtil util = new SSHClientUtil(ip, port, username, null);
        util.openConnectionByKey(privateKeyPath, passphrase);
        return util;
    }

    /**
     * 按连接信息自动选择认证方式：配置了可用私钥就先走公钥，失败再回落到密码。
     * <p>
     * 认证失败时抛出的 IOException 里会带上可读的原因（私钥不存在 / 私钥格式不对 / 密码错误），
     * 由调用方直接展示给用户。
     */
    public static SSHClientUtil connect(ConnectionInfo info) throws IOException {
        if (null == info) {
            throw new IOException("连接信息为空");
        }
        String host = info.getIdHost();
        if (StrUtil.isBlank(host)) {
            throw new IOException("主机地址为空");
        }
        int port;
        try {
            port = StrUtil.isBlank(info.getCdPort()) ? 22 : Integer.parseInt(info.getCdPort().trim());
        } catch (NumberFormatException e) {
            throw new IOException("端口不是合法数字：" + info.getCdPort());
        }
        String user = info.getIdUser();
        String password = info.getCdPassword();
        String keyPath = info.getCdKeyPath();

        StringBuilder reasons = new StringBuilder();
        if (StrUtil.isNotBlank(keyPath)) {
            File keyFile = new File(keyPath);
            if (!keyFile.isFile()) {
                reasons.append("私钥文件不存在(").append(keyPath).append(")");
                log.warn("{} 配置的私钥文件不存在：{}", host, keyPath);
            } else {
                try {
                    return byPrivateKey(host, port, user, keyPath, password);
                } catch (IOException e) {
                    String reason = e.getMessage();
                    reasons.append("私钥认证失败(").append(reason).append(")");
                    if (isOpenSshNewFormat(keyFile)) {
                        reasons.append("；该私钥是 OpenSSH 新格式，sshj 解析不了，"
                                + "请在服务器上执行 ssh-keygen -p -m PEM -f <私钥文件> 转成 PEM 后再上传");
                    }
                    log.warn("{} 使用私钥 {} 认证失败：{}", host, keyPath, reason);
                }
            }
            if (StrUtil.isBlank(password)) {
                throw new IOException(reasons.toString());
            }
        }
        if (StrUtil.isBlank(password)) {
            throw new IOException("既没有配置登录密码，也没有可用的私钥");
        }
        SSHClientUtil util = new SSHClientUtil(host, port, user, password);
        try {
            util.openConnection();
        } catch (IOException e) {
            if (reasons.length() > 0) {
                reasons.append("；密码认证也失败(").append(e.getMessage()).append(")");
                throw new IOException(reasons.toString(), e);
            }
            throw e;
        }
        return util;
    }

    /** 是否是 ssh-keygen 默认产出的新格式私钥（sshj 0.31 不支持） */
    private static boolean isOpenSshNewFormat(File keyFile) {
        byte[] buf = new byte[64];
        try (InputStream in = new FileInputStream(keyFile)) {
            int read = in.read(buf);
            if (read <= 0) {
                return false;
            }
            return new String(buf, 0, read, StandardCharsets.US_ASCII).contains("BEGIN OPENSSH PRIVATE KEY");
        } catch (IOException e) {
            return false;
        }
    }

    // 打开SSH连接（密码方式）
    public void openConnection() throws IOException {
        SSHClient client = newSshClient();
        try {
            client.connect(ip, port);
            client.authPassword(username, password);
        } catch (IOException e) {
            closeQuietly(client);
            throw e;
        }
        sshClient = client;
        afterConnect();
        log.info("Connected to {}", ip);
    }

    /**
     * 私钥方式连接。
     * <p>
     * 这里显式地 {@code loadKeys} 并主动取一次私钥，
     * 就是为了不让 sshj 把"密钥读不出来"这个原因吞掉。
     */
    public void openConnectionByKey(String privateKeyPath, String passphrase) throws IOException {
        File keyFile = new File(privateKeyPath);
        if (!keyFile.isFile()) {
            throw new IOException("私钥文件不存在：" + privateKeyPath);
        }
        SSHClient client = newSshClient();
        try {
            client.connect(ip, port);
            KeyProvider keyProvider = StrUtil.isBlank(passphrase)
                    ? client.loadKeys(privateKeyPath)
                    : client.loadKeys(privateKeyPath, passphrase.toCharArray());
            PrivateKey privateKey = keyProvider.getPrivate();
            if (null == privateKey) {
                throw new IOException("私钥解析失败，未取到私钥内容：" + privateKeyPath);
            }
            client.authPublickey(username, keyProvider);
        } catch (IOException e) {
            closeQuietly(client);
            throw e;
        }
        sshClient = client;
        afterConnect();
        log.info("Connected to {} by private key", ip);
    }

    private SSHClient newSshClient() {
        SSHClient client = new SSHClient();
        // 仅用于测试，生产环境中请使用更安全的 HostKeyVerifier 实现
        client.addHostKeyVerifier(new PromiscuousVerifier());
        // 超时必须在 connect 之前设置，连接建立后再设置对本次连接无效
        client.setConnectTimeout(CONNECT_TIMEOUT_MS);
        client.setTimeout(SO_TIMEOUT_MS);
        return client;
    }

    private void afterConnect() throws IOException {
        sshClient.getConnection().getKeepAlive().setKeepAliveInterval(KEEP_ALIVE_SECONDS);
    }

    private void closeQuietly(SSHClient client) {
        if (null == client) {
            return;
        }
        try {
            client.disconnect();
            client.close();
        } catch (IOException e) {
            log.warn("关闭连接失败：{}", e.getMessage());
        }
    }

    /**
     * 连接是否可用：SSHClient 已连接且已完成认证。
     * 共享连接池（{@link SshConnectionPool}）在出借前用它探测，
     * 死连接（网络断开、服务端空闲掐掉）会被丢弃重建。
     */
    public boolean isConnected() {
        return sshClient != null && sshClient.isConnected() && sshClient.isAuthenticated();
    }

    // 关闭SSH连接
    public void closeConnection() {
        if (sftpClient != null) {
            try {
                sftpClient.close();
            } catch (IOException e) {
                log.warn("关闭 sftp 失败：{}", e.getMessage());
            } finally {
                sftpClient = null;
            }
        }
        if (sshClient != null) {
            try {
                sshClient.disconnect();
                sshClient.close();
                log.info("Connection closed");
            } catch (IOException e) {
                log.error("Error closing SSH connection: {}", e.getMessage());
            } finally {
                sshClient = null;
            }
        }
    }

    // 执行单条命令
    public String executeCommand(String command) throws IOException {
        if (null == sshClient) {
            throw new IOException("SSH 连接未建立，无法执行命令：" + command);
        }
        try (Session startSession = sshClient.startSession()) {
            try (Session.Command cmd = startSession.exec(command)) {
                String output = IOUtils.readFully(cmd.getInputStream()).toString(); // 读取命令输出
                cmd.join(5, TimeUnit.SECONDS); // 等待命令执行完成
                return output;
            }
        }
    }

    // 执行多条命令
    public void executeCommands(List<String> commands) throws IOException {
        for (String command : commands) {
            executeCommand(command);
        }
    }

    /**
     * 打开一个命令通道并把输出作为流返回，用于「边产出边消费」的长输出命令
     * （例如 {@code docker logs --tail 5000}、{@code cat 大日志}）。
     * <p>
     * {@link #executeCommand(String)} 会把整段输出先读进内存，导出大日志时既慢又费内存；
     * 这里返回的流直接对接 sshj 的通道输入流，读完（或中途 close）时
     * 会自动关掉 {@code Session.Command} 和 {@code Session}，不会泄漏通道。
     * <p>
     * <b>只读 stdout。</b>需要连 stderr 一起拿就在命令末尾自己加 {@code 2>&1}。
     * 调用方必须负责关闭返回的流。
     */
    public InputStream openCommandStream(String command) throws IOException {
        if (null == sshClient) {
            throw new IOException("SSH 连接未建立，无法执行命令：" + command);
        }
        Session session = sshClient.startSession();
        try {
            return new CommandInputStream(session, session.exec(command));
        } catch (Exception e) {
            try {
                session.close();
            } catch (Exception ignore) {
                // 关不掉也不影响主流程
            }
            if (e instanceof IOException) {
                throw (IOException) e;
            }
            throw new IOException("打开命令通道失败：" + e.getMessage(), e);
        }
    }

    /**
     * 把 {@code Session} + {@code Session.Command} 的生命周期绑在流上。
     */
    private static final class CommandInputStream extends InputStream {

        private final Session session;
        private final Session.Command command;
        private final InputStream in;

        CommandInputStream(Session session, Session.Command command) {
            this.session = session;
            this.command = command;
            this.in = command.getInputStream();
        }

        @Override
        public int read() throws IOException {
            return in.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return in.read(b, off, len);
        }

        @Override
        public int available() throws IOException {
            return in.available();
        }

        @Override
        public void close() throws IOException {
            try {
                in.close();
            } catch (IOException ignore) {
                // 忽略
            }
            try {
                command.close();
            } catch (Exception ignore) {
                // 忽略
            }
            try {
                session.close();
            } catch (Exception ignore) {
                // 忽略
            }
        }
    }

    // 上传文件并可选验证哈希值
    public boolean uploadFile(String localFilePath, String remoteFilePath, String localFileHash) throws IOException {
        getSftpClient().put(new FileSystemFile(localFilePath), remoteFilePath); // 上传文件
        log.info("Uploaded file from {} to {}", localFilePath, remoteFilePath);
        if (StrUtil.isNotEmpty(localFileHash)) {
            return verifyRemoteFileHash(remoteFilePath, localFileHash); // 验证远程文件哈希值
        }
        return true;
    }

    // 下载文件并验证文件大小
    public boolean downloadFile(String remoteFilePath, String localFilePath) throws IOException {
        getSftpClient().get(remoteFilePath, new FileSystemFile(localFilePath)); // 下载文件
        log.info("Downloaded file from {} to {}", remoteFilePath, localFilePath);
        return verifyFileSize(localFilePath, remoteFilePath); // 验证文件大小
    }

    // 验证远程文件哈希值
    public boolean verifyRemoteFileHash(String remoteFilePath, String localFileHash) throws IOException {
        String remoteCommand = String.format("md5sum %s | awk '{ print $1 }'", remoteFilePath);
        String remoteFileHash = executeCommand(remoteCommand).trim(); // 执行远程命令获取哈希值

        boolean result = localFileHash.equals(remoteFileHash); // 比较哈希值
        if (result) {
            log.info("File hash verification successful for file: {}", remoteFilePath);
        } else {
            log.error("File hash mismatch for file: {}. Local hash: {}, Remote hash: {}", remoteFilePath, localFileHash, remoteFileHash);
        }
        return result;
    }

    // 验证文件大小
    private boolean verifyFileSize(String localFilePath, String remoteFilePath) throws IOException {
        File localFile = new File(localFilePath);
        long localFileSize = localFile.length(); // 获取本地文件大小

        FileAttributes remoteFile = getSftpClient().stat(remoteFilePath);
        long remoteFileSize = remoteFile.getSize(); // 获取远程文件大小

        boolean result = localFileSize == remoteFileSize; // 比较文件大小
        if (result) {
            log.info("File size verification successful for file: {}", localFilePath);
        } else {
            log.error("File size mismatch for file: {}. Local size: {}, Remote size: {}", localFilePath, localFileSize, remoteFileSize);
        }
        return result;
    }

    // 列出远程目录文件
    public List<RemoteResourceInfo> listFiles(String remoteDirectory) throws IOException {
        return getSftpClient().ls(remoteDirectory); // 列出远程目录文件
    }

    /**
     * 共享的 SFTP 客户端。加了 synchronized：连接进共享池后可能被多个页面
     * 的后台线程同时取用，至少保证「创建 + 取用」不互相踩。
     */
    public synchronized SFTPClient getSftpClient() throws IOException {
        if (null == sftpClient) {
            if (null == sshClient) {
                throw new IOException("SSH 连接未建立，无法创建 SFTP 通道");
            }
            sftpClient = sshClient.newSFTPClient();
        }
        return sftpClient;
    }

}
