package dev.lavalink.youtube.plugin;

import dev.lavalink.youtube.plugin.pot.ExternalProviderConfig;

public class Pot {
    private String token;
    private String visitorData;
    private ExternalProviderConfig externalProvider;

    public String getToken() {
        return token != null && !token.isEmpty() ? token : null;
    }

    public String getVisitorData() {
        return visitorData != null && !visitorData.isEmpty() ? visitorData : null;
    }

    public ExternalProviderConfig getExternalProvider() {
        return externalProvider;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public void setVisitorData(String visitorData) {
        this.visitorData = visitorData;
    }

    public void setExternalProvider(ExternalProviderConfig externalProvider) {
        this.externalProvider = externalProvider;
    }
}
