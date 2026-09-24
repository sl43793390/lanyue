package com.sl.docker;

import cn.hutool.core.util.StrUtil;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 极简行级 diff（LCS 回溯）。
 * <p>
 * 用途是「改 compose 文件前先看一眼改了什么」，不是生成补丁文件，所以刻意不引
 * java-diff-utils 这类库：几行 LCS 就够，而且不用担心它跟项目的 JDK 8 目标冲突。
 * <p>
 * 行数可能是几百行，O(n·m) 的 DP 表完全撑得住；真到几千行时也应该改用外部 diff。
 */
public final class ComposeDiff {

    private ComposeDiff() {
    }

    public enum Type {
        /** 两边都一样 */
        SAME,
        /** 新增 */
        ADD,
        /** 删除 */
        DEL
    }

    public static final class Line implements Serializable {

        private static final long serialVersionUID = 1L;

        private final Type type;
        private final String text;
        /** 在旧文本里的行号，1 起；新增行为 0 */
        private final int oldNo;
        /** 在新文本里的行号，1 起；删除行为 0 */
        private final int newNo;

        Line(Type type, String text, int oldNo, int newNo) {
            this.type = type;
            this.text = text;
            this.oldNo = oldNo;
            this.newNo = newNo;
        }

        public Type getType() {
            return type;
        }

        public String getText() {
            return text;
        }

        public int getOldNo() {
            return oldNo;
        }

        public int getNewNo() {
            return newNo;
        }

        public String getPrefix() {
            if (Type.ADD == type) {
                return "+";
            }
            if (Type.DEL == type) {
                return "-";
            }
            return " ";
        }

        public String getLineNoText() {
            if (Type.ADD == type) {
                return "     " + newNo;
            }
            if (Type.DEL == type) {
                return oldNo + "     ";
            }
            return oldNo + " " + newNo;
        }
    }

    public static List<Line> diff(String oldText, String newText) {
        List<String> left = split(oldText);
        List<String> right = split(newText);
        int n = left.size();
        int m = right.size();
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                if (left.get(i).equals(right.get(j))) {
                    lcs[i][j] = lcs[i + 1][j + 1] + 1;
                } else {
                    lcs[i][j] = Math.max(lcs[i + 1][j], lcs[i][j + 1]);
                }
            }
        }
        List<Line> result = new ArrayList<Line>();
        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (left.get(i).equals(right.get(j))) {
                result.add(new Line(Type.SAME, left.get(i), i + 1, j + 1));
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                result.add(new Line(Type.DEL, left.get(i), i + 1, 0));
                i++;
            } else {
                result.add(new Line(Type.ADD, right.get(j), 0, j + 1));
                j++;
            }
        }
        while (i < n) {
            result.add(new Line(Type.DEL, left.get(i), i + 1, 0));
            i++;
        }
        while (j < m) {
            result.add(new Line(Type.ADD, right.get(j), 0, j + 1));
            j++;
        }
        return result;
    }

    /** 有没有真正的差异（忽略纯空白行的增删也能算有差异，这里只判断内容是否完全一致） */
    public static boolean hasChange(List<Line> lines) {
        for (Line line : lines) {
            if (Type.SAME != line.getType()) {
                return true;
            }
        }
        return false;
    }

    public static int count(List<Line> lines, Type type) {
        int count = 0;
        for (Line line : lines) {
            if (type == line.getType()) {
                count++;
            }
        }
        return count;
    }

    /**
     * 渲染成 HTML：只显示差异行 + 每处差异前后各两行上下文，避免整份文件刷屏。
     */
    public static String toHtml(List<Line> lines) {
        StringBuilder sb = new StringBuilder();
        int context = 2;
        int lastShown = -1;
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            if (Type.SAME == line.getType() && !nearChange(lines, i, context)) {
                continue;
            }
            if (lastShown >= 0 && i - lastShown > 1) {
                sb.append("<span class=\"hunk\">        … 省略 ").append(i - lastShown - 1)
                        .append(" 行未改动内容 …</span>\n");
            }
            String cls = Type.ADD == line.getType() ? "add" : (Type.DEL == line.getType() ? "del" : "");
            String row = "<span class=\"ln\">" + escape(line.getLineNoText()) + "</span> "
                    + line.getPrefix() + " " + escape(line.getText());
            sb.append(cls.isEmpty() ? row : "<span class=\"" + cls + "\">" + row + "</span>");
            sb.append('\n');
            lastShown = i;
        }
        return sb.toString();
    }

    private static boolean nearChange(List<Line> lines, int index, int context) {
        for (int i = Math.max(0, index - context); i <= Math.min(lines.size() - 1, index + context); i++) {
            if (Type.SAME != lines.get(i).getType()) {
                return true;
            }
        }
        return false;
    }

    private static List<String> split(String text) {
        List<String> lines = new ArrayList<String>();
        if (null == text) {
            return lines;
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.endsWith("\n")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (StrUtil.isEmpty(normalized)) {
            return lines;
        }
        for (String line : normalized.split("\n", -1)) {
            lines.add(line);
        }
        return lines;
    }

    public static String escape(String text) {
        if (null == text) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
