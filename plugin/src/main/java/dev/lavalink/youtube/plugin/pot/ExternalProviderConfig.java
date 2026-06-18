package dev.lavalink.youtube.plugin.pot;

/**
 * Config bound from {@code plugins.youtube.pot.externalProvider}.
 */
public class ExternalProviderConfig {
    private boolean enabled = false;
    private String command;
    private long timeoutMs = 5000;
    private long criticalPathTimeoutMs = 0;
    private long cacheTtlSeconds = 300;
    private boolean playerTokenEnabled = false;
    private boolean gvsTokenEnabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public String getCommand() {
        return command != null && !command.isEmpty() ? command : null;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public long getCriticalPathTimeoutMs() {
        return criticalPathTimeoutMs;
    }

    public long getCacheTtlSeconds() {
        return cacheTtlSeconds;
    }

    public boolean isPlayerTokenEnabled() {
        return playerTokenEnabled;
    }

    public boolean isGvsTokenEnabled() {
        return gvsTokenEnabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public void setCriticalPathTimeoutMs(long criticalPathTimeoutMs) {
        this.criticalPathTimeoutMs = criticalPathTimeoutMs;
    }

    public void setCacheTtlSeconds(long cacheTtlSeconds) {
        this.cacheTtlSeconds = cacheTtlSeconds;
    }

    public void setPlayerTokenEnabled(boolean playerTokenEnabled) {
        this.playerTokenEnabled = playerTokenEnabled;
    }

    public void setGvsTokenEnabled(boolean gvsTokenEnabled) {
        this.gvsTokenEnabled = gvsTokenEnabled;
    }
}
