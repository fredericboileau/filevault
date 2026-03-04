package com.clevertricks.filevault;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("storage")
public class StorageProperties {

    private String location;
    private String host;
    private int port;
    private String db;
    private String username;
    private String password;
    private long maxFilesSize;

    public String getLocation() {
        return location;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getDb() {
        return db;
    }

    public void setDb(String db) {
        this.db = db;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public long getMaxFilesSize() {
        return maxFilesSize;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public void setMaxFilesSize(long maxFilesSize) {
        this.maxFilesSize = maxFilesSize;
    }
}
