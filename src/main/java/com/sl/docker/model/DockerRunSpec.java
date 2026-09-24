package com.sl.docker.model;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 创建容器（{@code docker run}）的参数。
 */
public class DockerRunSpec implements Serializable {

    private static final long serialVersionUID = 1L;

    private String image;
    private String name;
    private String command;
    /** 形如 {@code 8080:80} 或 {@code 8080:80/udp} */
    private List<String> ports = new ArrayList<String>();
    /** 形如 {@code KEY=VALUE} */
    private List<String> envs = new ArrayList<String>();
    /** 形如 {@code /host:/container} 或 {@code /host:/container:ro}，也支持卷名 */
    private List<String> volumes = new ArrayList<String>();
    private String network;
    /** no / on-failure / always / unless-stopped */
    private String restartPolicy;
    private boolean autoRemove;
    private boolean privileged;
    private String workDir;
    private String hostName;

    public List<String> buildRunArguments() {
        List<String> args = new ArrayList<String>();
        args.add("run");
        args.add("-d");
        if (StrUtil.isNotBlank(name)) {
            args.add("--name");
            args.add(name.trim());
        }
        if (StrUtil.isNotBlank(restartPolicy)) {
            args.add("--restart");
            args.add(restartPolicy.trim());
        }
        if (autoRemove) {
            args.add("--rm");
        }
        if (privileged) {
            args.add("--privileged");
        }
        if (StrUtil.isNotBlank(network)) {
            args.add("--network");
            args.add(network.trim());
        }
        if (StrUtil.isNotBlank(hostName)) {
            args.add("--hostname");
            args.add(hostName.trim());
        }
        if (StrUtil.isNotBlank(workDir)) {
            args.add("-w");
            args.add(workDir.trim());
        }
        for (String port : ports) {
            if (StrUtil.isNotBlank(port)) {
                args.add("-p");
                args.add(port.trim());
            }
        }
        for (String env : envs) {
            if (StrUtil.isNotBlank(env)) {
                args.add("-e");
                args.add(env.trim());
            }
        }
        for (String volume : volumes) {
            if (StrUtil.isNotBlank(volume)) {
                args.add("-v");
                args.add(volume.trim());
            }
        }
        args.add(image.trim());
        if (StrUtil.isNotBlank(command)) {
            // 命令要按空格切开分别作为参数，否则 docker 会把它当成一个可执行文件名
            for (String part : command.trim().split("\\s+")) {
                if (!part.isEmpty()) {
                    args.add(part);
                }
            }
        }
        return args;
    }

    /** 把多行文本按行拆成列表，顺手过滤空行与注释 */
    public static List<String> parseLines(String text) {
        List<String> result = new ArrayList<String>();
        if (StrUtil.isBlank(text)) {
            return result;
        }
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            result.add(trimmed);
        }
        return result;
    }

    /**
     * 给绑定挂载追加 SELinux 标签（{@code z} / {@code Z}）。
     * <p>
     * Rocky Linux 全系默认 SELinux enforcing，把宿主目录挂进容器时如果不打标签，
     * 容器内的进程会直接 permission denied —— 这是从 Ubuntu/未开 SELinux 的机器
     * 迁到 Rocky 时最常见的"莫名其妙挂载失败"。
     * <p>
     * 只处理绑定挂载（{@code /宿主路径:容器路径}），命名卷由 docker 自己管理标签，不需要也不该加。
     * 已经带 {@code z}/{@code Z} 的原样返回，不重复追加。
     */
    public static String applySelinuxLabel(String volumeSpec, String label) {
        if (StrUtil.isBlank(volumeSpec) || StrUtil.isBlank(label)) {
            return volumeSpec;
        }
        String spec = volumeSpec.trim();
        int firstColon = spec.indexOf(':');
        if (firstColon <= 0) {
            // 单段写法（只有容器路径，匿名卷），不是绑定挂载
            return spec;
        }
        String source = spec.substring(0, firstColon);
        boolean bindMount = source.startsWith("/") || source.startsWith("./") || source.startsWith("../")
                || source.startsWith("~");
        if (!bindMount) {
            // 命名卷，例如 mydata:/var/lib/mysql
            return spec;
        }
        String rest = spec.substring(firstColon + 1);
        String dest;
        String options = "";
        int lastColon = rest.lastIndexOf(':');
        if (lastColon > 0 && isMountOptionToken(rest.substring(lastColon + 1))) {
            dest = rest.substring(0, lastColon);
            options = rest.substring(lastColon + 1);
        } else {
            dest = rest;
        }
        if (options.indexOf('z') >= 0 || options.indexOf('Z') >= 0) {
            return spec;
        }
        String merged = options.isEmpty() ? label : options + "," + label;
        return source + ":" + dest + ":" + merged;
    }

    /** 判断是否只由 ro/rw/z/Z 组合而成，避免把「主机:容器:额外冒号」里的冒号误判成权限位 */
    private static boolean isMountOptionToken(String token) {
        if (StrUtil.isBlank(token)) {
            return false;
        }
        for (String part : token.split(",")) {
            String item = part.trim();
            if (!"ro".equals(item) && !"rw".equals(item) && !"z".equals(item) && !"Z".equals(item)) {
                return false;
            }
        }
        return true;
    }

    public boolean hasPorts() {
        return CollectionUtil.isNotEmpty(ports);
    }

    public String getImage() {
        return image;
    }

    public void setImage(String image) {
        this.image = image;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public List<String> getPorts() {
        return ports;
    }

    public void setPorts(List<String> ports) {
        this.ports = ports == null ? new ArrayList<String>() : ports;
    }

    public List<String> getEnvs() {
        return envs;
    }

    public void setEnvs(List<String> envs) {
        this.envs = envs == null ? new ArrayList<String>() : envs;
    }

    public List<String> getVolumes() {
        return volumes;
    }

    public void setVolumes(List<String> volumes) {
        this.volumes = volumes == null ? new ArrayList<String>() : volumes;
    }

    public String getNetwork() {
        return network;
    }

    public void setNetwork(String network) {
        this.network = network;
    }

    public String getRestartPolicy() {
        return restartPolicy;
    }

    public void setRestartPolicy(String restartPolicy) {
        this.restartPolicy = restartPolicy;
    }

    public boolean isAutoRemove() {
        return autoRemove;
    }

    public void setAutoRemove(boolean autoRemove) {
        this.autoRemove = autoRemove;
    }

    public boolean isPrivileged() {
        return privileged;
    }

    public void setPrivileged(boolean privileged) {
        this.privileged = privileged;
    }

    public String getWorkDir() {
        return workDir;
    }

    public void setWorkDir(String workDir) {
        this.workDir = workDir;
    }

    public String getHostName() {
        return hostName;
    }

    public void setHostName(String hostName) {
        this.hostName = hostName;
    }
}
