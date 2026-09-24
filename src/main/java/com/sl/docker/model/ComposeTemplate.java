package com.sl.docker.model;

import cn.hutool.core.util.StrUtil;

import java.io.Serializable;

/**
 * Compose 模板：一键生成一份可用的 compose 文件 + 配套 .env。
 * <p>
 * 模板里的可变部分一律写成 {@code ${VAR:-默认值}}。这样有两个好处：
 * 直接 up 就能跑（有默认值兜底），而用户想改的时候又只需要动 .env，
 * 不用去 yml 里翻找散落各处的端口号 / 密码 / 版本号。
 */
public class ComposeTemplate implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String name;
    private final String description;
    private final String yaml;
    private final String env;

    public ComposeTemplate(String name, String description, String yaml, String env) {
        this.name = name;
        this.description = description;
        this.yaml = yaml;
        this.env = StrUtil.emptyToDefault(env, "");
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getYaml() {
        return yaml;
    }

    public String getEnv() {
        return env;
    }

    @Override
    public String toString() {
        return name;
    }
}
