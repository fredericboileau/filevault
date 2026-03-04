#!/bin/bash

KEYCLOAK_URL="http://localhost:8180"
REALM="filevault"
CLIENT_ID="filevault-app"
CLIENT_SECRET="filevault-secret"
REDIRECT_URI="http://localhost:8080/login/oauth2/code/keycloak"

echo "Getting admin token..."
TOKEN=$(curl -sf -X POST "$KEYCLOAK_URL/realms/master/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "username=admin&password=admin&grant_type=password&client_id=admin-cli" |
  jq -r '.access_token')

echo "Creating realm '$REALM'..."
STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$KEYCLOAK_URL/admin/realms" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"realm\": \"$REALM\", \"enabled\": true, \"registrationAllowed\": true}")
if [ "$STATUS" = "201" ]; then
  echo "Realm created."
elif [ "$STATUS" = "409" ]; then
  echo "Realm already exists, skipping."
else
  echo "Unexpected status $STATUS creating realm, aborting." && exit 1
fi

echo "Creating client '$CLIENT_ID'..."
STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$KEYCLOAK_URL/admin/realms/$REALM/clients" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{
    \"clientId\": \"$CLIENT_ID\",
    \"enabled\": true,
    \"publicClient\": false,
    \"standardFlowEnabled\": true,
\"secret\": \"$CLIENT_SECRET\",
    \"redirectUris\": [\"$REDIRECT_URI\"],
    \"webOrigins\": [\"http://localhost:8080\"],
    \"attributes\": {\"post.logout.redirect.uris\": \"http://localhost:8080\"}
  }")
if [ "$STATUS" = "201" ]; then
  echo "Client created. Secret is: $CLIENT_SECRET"
elif [ "$STATUS" = "409" ]; then
  echo "Client already exists, skipping."
else
  echo "Unexpected status $STATUS creating client, aborting." && exit 1
fi

echo "Adding realm roles protocol mapper to ID token..."
CLIENT_UUID=$(curl -sf "$KEYCLOAK_URL/admin/realms/$REALM/clients?clientId=$CLIENT_ID" \
  -H "Authorization: Bearer $TOKEN" |
  jq -r '.[0].id')
STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X POST \
  "$KEYCLOAK_URL/admin/realms/$REALM/clients/$CLIENT_UUID/protocol-mappers/models" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{
    \"name\": \"realm roles\",
    \"protocol\": \"openid-connect\",
    \"protocolMapper\": \"oidc-usermodel-realm-role-mapper\",
    \"consentRequired\": false,
    \"config\": {
      \"claim.name\": \"realm_access.roles\",
      \"jsonType.label\": \"String\",
      \"multivalued\": \"true\",
      \"userinfo.token.claim\": \"true\",
      \"id.token.claim\": \"true\",
      \"access.token.claim\": \"true\"
    }
  }")
if [ "$STATUS" = "201" ]; then
  echo "Protocol mapper added."
elif [ "$STATUS" = "409" ]; then
  echo "Protocol mapper already exists, skipping."
else
  echo "Unexpected status $STATUS adding protocol mapper, aborting." && exit 1
fi

echo "Adding client roles protocol mapper to ID token..."
STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X POST \
  "$KEYCLOAK_URL/admin/realms/$REALM/clients/$CLIENT_UUID/protocol-mappers/models" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{
    \"name\": \"client roles\",
    \"protocol\": \"openid-connect\",
    \"protocolMapper\": \"oidc-usermodel-client-role-mapper\",
    \"consentRequired\": false,
    \"config\": {
      \"claim.name\": \"resource_access.\${client_id}.roles\",
      \"jsonType.label\": \"String\",
      \"multivalued\": \"true\",
      \"userinfo.token.claim\": \"true\",
      \"id.token.claim\": \"true\",
      \"access.token.claim\": \"true\"
    }
  }")
if [ "$STATUS" = "201" ]; then
  echo "Protocol mapper added."
elif [ "$STATUS" = "409" ]; then
  echo "Protocol mapper already exists, skipping."
else
  echo "Unexpected status $STATUS adding protocol mapper, aborting." && exit 1
fi

create_user() {
  local USERNAME=$1
  local PASSWORD=$2
  echo "Creating user '$USERNAME'..."
  STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$KEYCLOAK_URL/admin/realms/$REALM/users" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "{
      \"username\": \"$USERNAME\",
      \"enabled\": true,
      \"credentials\": [{\"type\": \"password\", \"value\": \"$PASSWORD\", \"temporary\": false}]
    }")
  if [ "$STATUS" = "201" ]; then
    echo "User '$USERNAME' created."
  elif [ "$STATUS" = "409" ]; then
    echo "User '$USERNAME' already exists, skipping."
  else
    echo "Unexpected status $STATUS creating user '$USERNAME', aborting." && exit 1
  fi
}

echo "Registering event listener 'filevault-event-listener' on realm '$REALM'..."
curl -sf -X PUT "$KEYCLOAK_URL/admin/realms/$REALM" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"eventsListeners\": [\"jboss-logging\", \"filevault-event-listener\"]}"
echo "Event listener registered."

create_user "admin" "admin"

echo "Creating realm role 'admin'..."
STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$KEYCLOAK_URL/admin/realms/$REALM/roles" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"name\": \"admin\"}")
if [ "$STATUS" = "201" ]; then
  echo "Role 'admin' created."
elif [ "$STATUS" = "409" ]; then
  echo "Role 'admin' already exists, skipping."
else
  echo "Unexpected status $STATUS creating role, aborting." && exit 1
fi

echo "Assigning role 'admin' to user 'admin'..."
ADMIN_USER_ID=$(curl -sf "$KEYCLOAK_URL/admin/realms/$REALM/users?username=admin" \
  -H "Authorization: Bearer $TOKEN" |
  jq -r '.[0].id')
ADMIN_ROLE=$(curl -sf "$KEYCLOAK_URL/admin/realms/$REALM/roles/admin" \
  -H "Authorization: Bearer $TOKEN")
curl -sf -X POST "$KEYCLOAK_URL/admin/realms/$REALM/users/$ADMIN_USER_ID/role-mappings/realm" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "[$ADMIN_ROLE]"
echo "Role assigned."

echo "Creating client role 'admin' on '$CLIENT_ID'..."
STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$KEYCLOAK_URL/admin/realms/$REALM/clients/$CLIENT_UUID/roles" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"name\": \"admin\"}")
if [ "$STATUS" = "201" ]; then
  echo "Client role 'admin' created."
elif [ "$STATUS" = "409" ]; then
  echo "Client role 'admin' already exists, skipping."
else
  echo "Unexpected status $STATUS creating client role, aborting." && exit 1
fi

echo "Assigning client role 'admin' to user 'admin'..."
CLIENT_ADMIN_ROLE=$(curl -sf "$KEYCLOAK_URL/admin/realms/$REALM/clients/$CLIENT_UUID/roles/admin" \
  -H "Authorization: Bearer $TOKEN")
curl -sf -X POST "$KEYCLOAK_URL/admin/realms/$REALM/users/$ADMIN_USER_ID/role-mappings/clients/$CLIENT_UUID" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "[$CLIENT_ADMIN_ROLE]"
echo "Client role assigned."

echo ""
echo "Done. Realm '$REALM' is ready with user admin/admin (realm+client admin role)."
