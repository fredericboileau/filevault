package com.clevertricks;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import org.keycloak.Config;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventListenerProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

public class FileVaultEventListenerProviderFactory implements EventListenerProviderFactory {

    public static final String ID = "filevault-event-listener";

    private Connection conn;

    @Override
    public EventListenerProvider create(KeycloakSession session) {
        return new FileVaultEventListenerProvider(session, conn);
    }

    @Override
    public void init(Config.Scope config) {
        String url = config.get("db-url");
        String username = config.get("db-username");
        String password = config.get("db-password");
        try {
            conn = DriverManager.getConnection(url, username, password);
            System.out.println("Connection: " + conn);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to connect to database", e);
        }
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
    }

    @Override
    public void close() {
        try {
            if (conn != null)
                conn.close();
        } catch (SQLException e) {
            // ignore on shutdown
        }
    }

    @Override
    public String getId() {
        return ID;
    }
}
