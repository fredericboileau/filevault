COMPOSE_TEST = docker compose -f docker-compose.yml -f docker-compose.test.yml -p filevault-test

.PHONY: clean start-infra start-app test

clean:
	docker compose down
	docker volume rm fileuploader_db_data fileuploader_fs_data

start-infra:
	docker compose up -d db keycloak
	docker compose up wait-for-keycloak
	keycloak/setuprealm.py

start-app:
	docker compose up -d --build app
	cd frontend && npm run dev

test:
	$(COMPOSE_TEST) up -d db keycloak
	$(COMPOSE_TEST) up wait-for-keycloak
	keycloak/setuprealm.py --test
	cd restapp && mvn test -Dtest=FileVaultE2ETest; EXIT=$$?; \
	cd .. && $(COMPOSE_TEST) down -v; \
	exit $$EXIT
