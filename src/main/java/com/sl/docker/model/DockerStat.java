package com.sl.docker.model;

import cn.hutool.core.util.NumberUtil;

import java.io.Serializable;

/**
 * 一次 {@code docker stats --no-stream} 采样。
 */
public class DockerStat implements Serializable {

    private static final long serialVersionUID = 1L;

    private String containerId;
    private String name;
    /** CPU 百分比，0-100（多核可能超过 100，不做截断） */
    private double cpuPercent;
    private double memPercent;
    private long memUsageBytes;
    private long memLimitBytes;
    private long netInputBytes;
    private long netOutputBytes;
    private long blockInputBytes;
    private long blockOutputBytes;
    private int pids;

    public String getMemUsageText() {
        return formatBytes(memUsageBytes) + " / " + formatBytes(memLimitBytes);
    }

    public String getNetIoText() {
        return formatBytes(netInputBytes) + " / " + formatBytes(netOutputBytes);
    }

    public String getBlockIoText() {
        return formatBytes(blockInputBytes) + " / " + formatBytes(blockOutputBytes);
    }

    public String getCpuText() {
        return NumberUtil.round(cpuPercent, 2) + "%";
    }

    public String getMemPercentText() {
        return NumberUtil.round(memPercent, 2) + "%";
    }

    /** 把 docker 输出的 "12.3MiB" / "1.2GB" / "512B" 转成字节数 */
    public static long parseBytes(String text) {
        if (text == null) {
            return 0L;
        }
        String value = text.trim();
        if (value.isEmpty() || "--".equals(value)) {
            return 0L;
        }
        int i = 0;
        while (i < value.length() && (Character.isDigit(value.charAt(i)) || value.charAt(i) == '.')) {
            i++;
        }
        if (i == 0) {
            return 0L;
        }
        double number;
        try {
            number = Double.parseDouble(value.substring(0, i));
        } catch (NumberFormatException e) {
            return 0L;
        }
        String unit = value.substring(i).trim().toLowerCase();
        double factor;
        if (unit.startsWith("t")) {
            factor = 1024d * 1024 * 1024 * 1024;
        } else if (unit.startsWith("g")) {
            factor = 1024d * 1024 * 1024;
        } else if (unit.startsWith("m")) {
            factor = 1024d * 1024;
        } else if (unit.startsWith("k")) {
            factor = 1024d;
        } else {
            factor = 1d;
        }
        return (long) (number * factor);
    }

    public static String formatBytes(long bytes) {
        if (bytes < 0) {
            return "-";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024d;
        if (kb < 1024) {
            return NumberUtil.round(kb, 1) + " KB";
        }
        double mb = kb / 1024d;
        if (mb < 1024) {
            return NumberUtil.round(mb, 1) + " MB";
        }
        double gb = mb / 1024d;
        if (gb < 1024) {
            return NumberUtil.round(gb, 2) + " GB";
        }
        return NumberUtil.round(gb / 1024d, 2) + " TB";
    }

    public String getContainerId() {
        return containerId;
    }

    public void setContainerId(String containerId) {
        this.containerId = containerId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public double getCpuPercent() {
        return cpuPercent;
    }

    public void setCpuPercent(double cpuPercent) {
        this.cpuPercent = cpuPercent;
    }

    public double getMemPercent() {
        return memPercent;
    }

    public void setMemPercent(double memPercent) {
        this.memPercent = memPercent;
    }

    public long getMemUsageBytes() {
        return memUsageBytes;
    }

    public void setMemUsageBytes(long memUsageBytes) {
        this.memUsageBytes = memUsageBytes;
    }

    public long getMemLimitBytes() {
        return memLimitBytes;
    }

    public void setMemLimitBytes(long memLimitBytes) {
        this.memLimitBytes = memLimitBytes;
    }

    public long getNetInputBytes() {
        return netInputBytes;
    }

    public void setNetInputBytes(long netInputBytes) {
        this.netInputBytes = netInputBytes;
    }

    public long getNetOutputBytes() {
        return netOutputBytes;
    }

    public void setNetOutputBytes(long netOutputBytes) {
        this.netOutputBytes = netOutputBytes;
    }

    public long getBlockInputBytes() {
        return blockInputBytes;
    }

    public void setBlockInputBytes(long blockInputBytes) {
        this.blockInputBytes = blockInputBytes;
    }

    public long getBlockOutputBytes() {
        return blockOutputBytes;
    }

    public void setBlockOutputBytes(long blockOutputBytes) {
        this.blockOutputBytes = blockOutputBytes;
    }

    public int getPids() {
        return pids;
    }

    public void setPids(int pids) {
        this.pids = pids;
    }
}
