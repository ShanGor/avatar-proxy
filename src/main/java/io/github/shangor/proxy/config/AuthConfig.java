package io.github.shangor.proxy.config;

import java.util.HashMap;
import java.util.Map;

public class AuthConfig {
    private boolean authEnabled = false;
    private final Map<String, String> credentials = new HashMap<>();

    public boolean isAuthEnabled() {
        return authEnabled;
    }

    public void setAuthEnabled(boolean authEnabled) {
        this.authEnabled = authEnabled;
    }

    public Map<String, String> getCredentials() {
        return credentials;
    }

    public void addCredential(String username, String password) {
        if (username != null && !username.isEmpty() && password != null) {
            credentials.put(username, password);
            authEnabled = true;
        }
    }

    public boolean authenticate(String username, String password) {
        if (!authEnabled) {
            return true; // If authentication is not enabled, always return true
        }
        
        if (username == null || password == null) {
            return false;
        }
        
        String storedPassword = credentials.get(username);
        return storedPassword != null && storedPassword.equals(password);
    }
}