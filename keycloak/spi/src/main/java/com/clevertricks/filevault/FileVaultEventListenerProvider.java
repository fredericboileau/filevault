package com.clevertricks.filevault;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

import org.keycloak.events.Event;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventType;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.events.admin.OperationType;
import org.keycloak.events.admin.ResourceType;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;

public class FileVaultEventListenerProvider implements EventListenerProvider {

    private final KeycloakSession session;
    private final Connection conn;

    public FileVaultEventListenerProvider(KeycloakSession session, Connection conn) {
        this.session = session;
        this.conn = conn;
    }

    @Override
    public void onEvent(Event event) {
        String userId = event.getUserId();
        if (userId == null)
            return;

        RealmModel realm = session.realms().getRealm(event.getRealmId());
        var user = session.users().getUserById(realm, userId);
        String username = user != null ? user.getUsername() : userId;

        var sb = new StringBuilder();
        Map<String, String> detailsMap = event.getDetails();
        if (detailsMap != null) {
            detailsMap.forEach((k, v) -> sb.append(k).append(" ").append(v).append(" "));
        }
        System.out.println(username + " " + sb.toString());

        if (event.getType() == EventType.REGISTER) {
            insertUser(userId, username);
        } else if (event.getType() == EventType.DELETE_ACCOUNT) {
            deleteUser(userId);
        }
    }

    @Override
    public void onEvent(AdminEvent event, boolean includeRepresentation) {

        if (includeRepresentation) {
            System.out.println(event.getRepresentation());
        }

        if (event.getResourceType() == ResourceType.USER) {
            String resourcePath = event.getResourcePath(); // "users/<uuid>"
            String userId = resourcePath.substring(resourcePath.lastIndexOf('/') + 1);
            if (event.getOperationType() == OperationType.CREATE) {
                RealmModel realm = session.realms().getRealm(event.getRealmId());
                var user = session.users().getUserById(realm, userId);
                String username = user != null ? user.getUsername() : userId;
                insertUser(userId, username);
            } else if (event.getOperationType() == OperationType.DELETE) {
                deleteUser(userId);
            }
        }
    }

    private void deleteUser(String userId) {
        try {
            var sql = "delete from users where userId = ?";
            var stmt = conn.prepareStatement(sql);
            stmt.setObject(1, UUID.fromString(userId));
            stmt.executeUpdate();
            System.out.println("[FileVault] Deleted user: " + userId);
        } catch (SQLException e) {
            System.out.println("[FileVault] Couldn't delete " + userId + ": " + e.getMessage());
        }
    }

    private void insertUser(String userId, String username) {
        try {
            var sql = "insert into users (userId, username) values (?, ?)";
            var stmt = conn.prepareStatement(sql);
            stmt.setObject(1, UUID.fromString(userId));
            stmt.setString(2, username);
            stmt.executeUpdate();
            System.out.println("[FileVault] Inserted user: " + username);
        } catch (SQLException e) {
            System.out.println(
                    "[FileVault] Couldn't insert " + userId + " with username " + username + ": " + e.getMessage());
        }
    }

    @Override
    public void close() {
    }
}
