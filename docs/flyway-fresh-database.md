# Initializing a new Users database with Flyway

The normal application configuration keeps Flyway disabled and Hibernate on its existing setting. To initialize a **new, empty** PostgreSQL database, explicitly activate the `flyway-fresh` profile and provide both an expected database name and an operator confirmation.

The profile enables Flyway, disables automatic baselining and cleaning, and sets Hibernate to `validate`. Before Flyway runs, the guard checks that:

- the connected PostgreSQL database name exactly matches `USERS_FLYWAY_FRESH_DATABASE_NAME`;
- the configured Flyway schema is the connection's current schema;
- `USERS_FLYWAY_FRESH_CONFIRMATION` exactly equals `INITIALIZE_FRESH_DATABASE:<database-name>`;
- the target schema is empty, or it already has a complete, successful V1-V3 Flyway history that validates cleanly.

An unknown, partially populated, baseline-only, or partially migrated schema is rejected before migrations run. The profile does not infer that an existing database is safe from its name alone. For an existing installation, do not select this profile; use a separately reviewed inventory and migration procedure.

Example for an operator-approved new database named `usuariosdb_fresh`:

```powershell
$env:SPRING_PROFILES_ACTIVE = 'flyway-fresh'
$env:USERS_FLYWAY_FRESH_DATABASE_NAME = 'usuariosdb_fresh'
$env:USERS_FLYWAY_FRESH_CONFIRMATION = 'INITIALIZE_FRESH_DATABASE:usuariosdb_fresh'
# Set DB_URL, DB_USERNAME and DB_PASSWORD through the approved secret mechanism.
```

The confirmation is an explicit opt-in, not a database credential. The schema emptiness check and exact database-name check are the safeguards against accidental initialization. Operators must still verify the target endpoint and database identity before supplying the confirmation.

The PostgreSQL test runner uses its own disposable `migration_test` database and marker. Its tests create uniquely named schemas, then remove only those schemas inside that disposable database.
