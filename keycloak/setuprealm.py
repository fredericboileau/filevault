#!/usr/bin/env -S uv run --script
#
# /// script
# requires-python = ">=3.13"
# dependencies = [
#     "requests>=2.32.5",
# ]
# ///


import argparse
import requests
import sys

keycloak_url = "http://localhost:8180"
realm = "filevault"
client_id = "filevault-app"
client_secret = "filevault-secret"
redirect_uris = [
    "http://localhost:8080/login/oauth2/code/keycloak",
    "http://localhost:5173/*",
    "http://localhost:5173/",
]
web_origins = ["http://localhost:8080", "http://localhost:5173"]


def auth_header(token: str) -> dict:
    return {"Authorization": f"Bearer {token}"}


def get_admin_token() -> str:
    r = requests.post(
        f"{keycloak_url}/realms/master/protocol/openid-connect/token",
        data={"username": "admin", "password": "admin",
              "grant_type": "password", "client_id": "admin-cli"}
    )
    return r.json()["access_token"]


def create_realm(token: str) -> None:
    r = requests.post(
        f"{keycloak_url}/admin/realms",
        headers=auth_header(token),
        json={"realm": realm, "enabled": True, "registrationAllowed": True}
    )
    if r.status_code == 201:
        print("Realm created")
    elif r.status_code == 409:
        print("Realm already exists, skipping")
    else:
        print(f"Unexpected status {r.status_code} creating realm, aborting")
        sys.exit(1)


client_config = {
    "clientId": client_id, "enabled": True, "publicClient": True,
    "standardFlowEnabled": True, "directAccessGrantsEnabled": True,
    "redirectUris": redirect_uris,
    "webOrigins": web_origins,
    "attributes": {"post.logout.redirect.uris": "http://localhost:5173/"}
}


def create_client(token: str) -> str:
    r = requests.post(
        f"{keycloak_url}/admin/realms/{realm}/clients",
        headers=auth_header(token),
        json=client_config,
    )
    if r.status_code == 201:
        print("Client created (public)")
    elif r.status_code == 409:
        print("Client already exists, updating redirect URIs")
    else:
        print(f"Unexpected status {r.status_code} creating client, aborting")
        sys.exit(1)

    client_uuid = requests.get(
        f"{keycloak_url}/admin/realms/{realm}/clients?clientId={client_id}",
        headers=auth_header(token)
    ).json()[0]["id"]

    if r.status_code == 409:
        requests.put(
            f"{keycloak_url}/admin/realms/{realm}/clients/{client_uuid}",
            headers=auth_header(token),
            json=client_config,
        )

    return client_uuid


def add_protocol_mapper(token: str, client_uuid: str, name: str, mapper: str, claim: str) -> None:
    r = requests.post(
        f"{keycloak_url}/admin/realms/{realm}/clients/{client_uuid}/protocol-mappers/models",
        headers=auth_header(token),
        json={"name": name, "protocol": "openid-connect", "protocolMapper": mapper,
              "consentRequired": False,
              "config": {"claim.name": claim, "jsonType.label": "String",
                         "multivalued": "true", "userinfo.token.claim": "true",
                         "id.token.claim": "true", "access.token.claim": "true"}}
    )
    if r.status_code == 201:
        print(f"Protocol mapper '{name}' added")
    elif r.status_code == 409:
        print(f"Protocol mapper '{name}' already exists, skipping")
    else:
        print(f"Unexpected status {r.status_code} adding protocol mapper '{name}', aborting")
        sys.exit(1)


def register_event_listener(token: str) -> None:
    r = requests.put(
        f"{keycloak_url}/admin/realms/{realm}",
        headers=auth_header(token),
        json={"eventsListeners": ["jboss-logging", "filevault-event-listener"]}
    )
    if r.status_code in (200, 204):
        print("Event listener registered")
    else:
        print(f"Unexpected status {r.status_code} registering event listener, aborting")
        sys.exit(1)


def create_user(token: str, username: str, password: str) -> str:
    r = requests.post(
        f"{keycloak_url}/admin/realms/{realm}/users",
        headers=auth_header(token),
        json={"username": username, "enabled": True,
              "credentials": [{"type": "password", "value": password, "temporary": False}]}
    )
    if r.status_code == 201:
        print(f"User '{username}' created")
    elif r.status_code == 409:
        print(f"User '{username}' already exists, skipping")
    else:
        print(f"Unexpected status {r.status_code} creating user '{username}', aborting")
        sys.exit(1)

    return requests.get(
        f"{keycloak_url}/admin/realms/{realm}/users?username={username}",
        headers=auth_header(token)
    ).json()[0]["id"]


def create_realm_role(token: str, name: str) -> dict:
    r = requests.post(
        f"{keycloak_url}/admin/realms/{realm}/roles",
        headers=auth_header(token),
        json={"name": name}
    )
    if r.status_code == 201:
        print(f"Realm role '{name}' created")
    elif r.status_code == 409:
        print(f"Realm role '{name}' already exists, skipping")
    else:
        print(f"Unexpected status {r.status_code} creating realm role '{name}', aborting")
        sys.exit(1)

    return requests.get(
        f"{keycloak_url}/admin/realms/{realm}/roles/{name}",
        headers=auth_header(token)
    ).json()


def assign_realm_role(token: str, user_id: str, role: dict) -> None:
    requests.post(
        f"{keycloak_url}/admin/realms/{realm}/users/{user_id}/role-mappings/realm",
        headers=auth_header(token),
        json=[role]
    )
    print(f"Realm role '{role['name']}' assigned")


def create_client_role(token: str, client_uuid: str, name: str) -> dict:
    r = requests.post(
        f"{keycloak_url}/admin/realms/{realm}/clients/{client_uuid}/roles",
        headers=auth_header(token),
        json={"name": name}
    )
    if r.status_code == 201:
        print(f"Client role '{name}' created")
    elif r.status_code == 409:
        print(f"Client role '{name}' already exists, skipping")
    else:
        print(f"Unexpected status {r.status_code} creating client role '{name}', aborting")
        sys.exit(1)

    return requests.get(
        f"{keycloak_url}/admin/realms/{realm}/clients/{client_uuid}/roles/{name}",
        headers=auth_header(token)
    ).json()


def assign_client_role(token: str, client_uuid: str, user_id: str, role: dict) -> None:
    requests.post(
        f"{keycloak_url}/admin/realms/{realm}/users/{user_id}/role-mappings/clients/{client_uuid}",
        headers=auth_header(token),
        json=[role]
    )
    print(f"Client role '{role['name']}' assigned")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--test", action="store_true", help="Create test users alice and bob")
    args = parser.parse_args()

    token = get_admin_token()
    create_realm(token)
    client_uuid = create_client(token)
    add_protocol_mapper(token, client_uuid, "realm roles",
                        "oidc-usermodel-realm-role-mapper", "realm_access.roles")
    add_protocol_mapper(token, client_uuid, "client roles",
                        "oidc-usermodel-client-role-mapper", "resource_access.${client_id}.roles")
    register_event_listener(token)
    admin_user_id = create_user(token, "admin", "admin")
    admin_realm_role = create_realm_role(token, "admin")
    assign_realm_role(token, admin_user_id, admin_realm_role)
    admin_client_role = create_client_role(token, client_uuid, "admin")
    assign_client_role(token, client_uuid, admin_user_id, admin_client_role)

    if args.test:
        create_user(token, "alice", "alice")
        create_user(token, "bob", "bob")
        print(f"\nDone. Realm '{realm}' is ready with users admin/admin, alice/alice and bob/bob.")
    else:
        print(f"\nDone. Realm '{realm}' is ready with user admin/admin.")


if __name__ == "__main__":
    main()
