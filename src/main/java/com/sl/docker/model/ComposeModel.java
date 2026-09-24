package com.sl.docker.model;

import cn.hutool.core.util.StrUtil;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code docker compose config} 的解析结果 —— 也就是「最终生效的那份配置」。
 * <p>
 * 服务视图、依赖图、端口总览、变量检查全部基于它，而不是基于用户手写的 yml。
 * 因为只有 config 之后的结果才是 docker 真正会去执行的东西：
 * 多文件叠加、{@code ${VAR}} 替换、{@code env_file} 合并、默认值补全都已完成。
 */
public class ComposeModel implements Serializable {

    private static final long serialVersionUID = 1L;

    private String projectName = "";
    private List<ComposeServiceInfo> services = new ArrayList<ComposeServiceInfo>();
    private Map<String, ComposeServiceInfo> serviceMap = new LinkedHashMap<String, ComposeServiceInfo>();
    /** 顶层 networks 声明 */
    private List<String> declaredNetworks = new ArrayList<String>();
    /** 顶层 volumes 声明 */
    private List<String> declaredVolumes = new ArrayList<String>();
    /** 解析出来的全部端口映射 */
    private List<ComposePortMapping> ports = new ArrayList<ComposePortMapping>();
    /** config 展开后的 YAML 全文（变量已替换） */
    private String resolvedYaml = "";
    /** compose config 的警告（stderr），例如变量未设置 */
    private List<String> warnings = new ArrayList<String>();

    public ComposeServiceInfo service(String name) {
        return serviceMap.get(name);
    }

    /** 服务之间有无 depends_on 形成的环（有环说明配置有误，画出图来也是死循环） */
    public List<String> detectDependencyCycle() {
        List<String> problems = new ArrayList<String>();
        List<String> visiting = new ArrayList<String>();
        List<String> visited = new ArrayList<String>();
        for (ComposeServiceInfo service : services) {
            walk(service.getName(), visiting, visited, problems);
        }
        return problems;
    }

    private void walk(String name, List<String> visiting, List<String> visited, List<String> problems) {
        if (visited.contains(name)) {
            return;
        }
        if (visiting.contains(name)) {
            problems.add(StrUtil.join(" → ", visiting) + " → " + name);
            return;
        }
        ComposeServiceInfo service = serviceMap.get(name);
        if (null == service) {
            return;
        }
        visiting.add(name);
        for (String next : service.getDependsOn()) {
            walk(next, visiting, visited, problems);
        }
        visiting.remove(name);
        visited.add(name);
    }

    public String getProjectName() {
        return projectName;
    }

    public void setProjectName(String projectName) {
        this.projectName = StrUtil.emptyToDefault(projectName, "");
    }

    public List<ComposeServiceInfo> getServices() {
        return services;
    }

    public void setServices(List<ComposeServiceInfo> services) {
        this.services = (null == services) ? new ArrayList<ComposeServiceInfo>() : services;
    }

    public Map<String, ComposeServiceInfo> getServiceMap() {
        return serviceMap;
    }

    public void setServiceMap(Map<String, ComposeServiceInfo> serviceMap) {
        this.serviceMap = (null == serviceMap) ? new LinkedHashMap<String, ComposeServiceInfo>() : serviceMap;
    }

    public List<String> getDeclaredNetworks() {
        return declaredNetworks;
    }

    public void setDeclaredNetworks(List<String> declaredNetworks) {
        this.declaredNetworks = (null == declaredNetworks) ? new ArrayList<String>() : declaredNetworks;
    }

    public List<String> getDeclaredVolumes() {
        return declaredVolumes;
    }

    public void setDeclaredVolumes(List<String> declaredVolumes) {
        this.declaredVolumes = (null == declaredVolumes) ? new ArrayList<String>() : declaredVolumes;
    }

    public List<ComposePortMapping> getPorts() {
        return ports;
    }

    public void setPorts(List<ComposePortMapping> ports) {
        this.ports = (null == ports) ? new ArrayList<ComposePortMapping>() : ports;
    }

    public String getResolvedYaml() {
        return resolvedYaml;
    }

    public void setResolvedYaml(String resolvedYaml) {
        this.resolvedYaml = StrUtil.emptyToDefault(resolvedYaml, "");
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public void setWarnings(List<String> warnings) {
        this.warnings = (null == warnings) ? new ArrayList<String>() : warnings;
    }
}
