package com.gmscpt.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code gmscpt.*} 配置。启动时由 Spring 绑定，运行期可改的项通过 {@code /api/settings} 落库。
 */
@ConfigurationProperties(prefix = "gmscpt")
public class GmScriptProperties {

    /** 数据目录；留空则用 {@code ~/.gmscpt}。 */
    private String dataDir = "";

    /** 启动后自动打开浏览器。 */
    private boolean openBrowser = true;

    /** 关闭鉴权（仅本机开发用）。 */
    private boolean authDisabled = false;

    private final RuntimeSettings runtime = new RuntimeSettings();
    private final InputSettings input = new InputSettings();

    public String getDataDir() {
        return dataDir;
    }

    public void setDataDir(String dataDir) {
        this.dataDir = dataDir;
    }

    public boolean isOpenBrowser() {
        return openBrowser;
    }

    public void setOpenBrowser(boolean openBrowser) {
        this.openBrowser = openBrowser;
    }

    public boolean isAuthDisabled() {
        return authDisabled;
    }

    public void setAuthDisabled(boolean authDisabled) {
        this.authDisabled = authDisabled;
    }

    public RuntimeSettings getRuntime() {
        return runtime;
    }

    public InputSettings getInput() {
        return input;
    }

    /** 运行期限制。 */
    public static class RuntimeSettings {
        /** 每个脚本的事件队列上限：满则丢最旧并计数。 */
        private int eventQueueCapacity = 4096;
        /** 停止宽限期；超时判 KILLED。 */
        private long stopGraceMs = 3000;
        /** 单次运行最长时长；0 表示不限。 */
        private long maxRunMs = 0;
        /** 输入注入限速（每秒）。 */
        private int maxInputActionsPerSecond = 200;

        public int getEventQueueCapacity() {
            return eventQueueCapacity;
        }

        public void setEventQueueCapacity(int eventQueueCapacity) {
            this.eventQueueCapacity = eventQueueCapacity;
        }

        public long getStopGraceMs() {
            return stopGraceMs;
        }

        public void setStopGraceMs(long stopGraceMs) {
            this.stopGraceMs = stopGraceMs;
        }

        public long getMaxRunMs() {
            return maxRunMs;
        }

        public void setMaxRunMs(long maxRunMs) {
            this.maxRunMs = maxRunMs;
        }

        public int getMaxInputActionsPerSecond() {
            return maxInputActionsPerSecond;
        }

        public void setMaxInputActionsPerSecond(int maxInputActionsPerSecond) {
            this.maxInputActionsPerSecond = maxInputActionsPerSecond;
        }
    }

    /** 输入相关开关。 */
    public static class InputSettings {
        /**
         * 精确相对移动。当前实现用绝对坐标移动，本来就不受系统加速曲线影响，
         * 因此该项仅在需要切换回 SendInput 相对移动时才打开；
         * **任何情况下都不再修改系统鼠标速度**。
         */
        private boolean preciseRelativeMove = false;

        public boolean isPreciseRelativeMove() {
            return preciseRelativeMove;
        }

        public void setPreciseRelativeMove(boolean preciseRelativeMove) {
            this.preciseRelativeMove = preciseRelativeMove;
        }
    }
}
