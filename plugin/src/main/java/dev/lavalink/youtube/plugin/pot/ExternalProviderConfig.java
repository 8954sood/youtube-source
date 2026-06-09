package dev.lavalink.youtube.plugin.pot;

/**
 * Config bound from {@code plugins.youtube.pot.externalProvider}.
 */
public class ExternalProviderConfig {
    private boolean enabled = false;
    private String command;
    private long timeoutMs = 5000;
    private long cacheTtlSeconds = 300;

    public boolean isEnabled() {
        return enabled;
    }

    public String getCommand() {
        return command != null && !command.isEmpty() ? command : null;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public long getCacheTtlSeconds() {
        return cacheTtlSeconds;
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

    public void setCacheTtlSeconds(long cacheTtlSeconds) {
        this.cacheTtlSeconds = cacheTtlSeconds;
    }
}
