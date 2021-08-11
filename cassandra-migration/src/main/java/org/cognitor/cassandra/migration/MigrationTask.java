package org.cognitor.cassandra.migration;

import org.slf4j.Logger;

import java.util.List;

import static java.lang.String.format;
import static org.cognitor.cassandra.migration.util.Ensure.notNull;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * The migration task is managing the database migrations. It checks which
 * schema version is in the database and retrieves the migrations that
 * need to be applied from the repository. Those migrations are than
 * executed against the database.
 *
 * @author Patrick Kranz
 */
public class MigrationTask {
    private static final Logger LOGGER = getLogger(MigrationTask.class);

    private final Database database;
    private final MigrationRepository repository;
    private final boolean withConsensus;

    /**
     * This property decides whether or not, on a batch migration,
     * if any of the scripts fail, should the migration continue without it or not
     */
    private boolean shouldFailGracefully = false;

    /**
     * Creates a migration task that uses the given database and repository.
     *
     * @param database   the database that should be migrated
     * @param repository the repository that contains the migration scripts
     */
    public MigrationTask(Database database, MigrationRepository repository) {
        this(database, repository, false);
    }

    /**
     * Creates a migration task that uses the given database and repository.
     *
     * @param database      the database that should be migrated
     * @param repository    the repository that contains the migration scripts
     * @param withConsensus if the migration should be handled by a single process at once, using LWT based leader election
     */
    public MigrationTask(Database database, MigrationRepository repository, boolean withConsensus) {
        this.database = notNull(database, "database");
        this.repository = notNull(repository, "repository");
        this.withConsensus = notNull(withConsensus, "withConsensus");
    }

    /**
     * Start the actual migration. Take the version of the database, get all required migrations and execute them or do
     * nothing if the DB is already up to date.
     * <p>
     * At the end the underlying database instance is closed.
     *
     * @throws MigrationException if a migration fails
     */
    public void migrate() {
        if (databaseIsUpToDate()) {
            LOGGER.info(format("Keyspace %s is already up to date at version %d", database.getKeyspaceName(),
                    database.getVersion()));
            return;
        }

        try {
            if (!withConsensus || database.takeLeadOnMigrations(repository.getLatestVersion())) {
                if (!databaseIsUpToDate()) {
                    List<DbMigration> migrations = repository.getMigrationsSinceVersion(database.getVersion());
                    migrations.forEach(migration -> {
                        if (shouldFailGracefully()) {
                            migrateWithHandler(migration);
                        } else {
                            database.execute(migration);
                        }
                    });
                    LOGGER.info(format("Migrated keyspace %s to version %d", database.getKeyspaceName(),
                            database.getVersion()));
                }
            }
        } finally {
            if (withConsensus) {
                database.removeLeadOnMigrations();
            }
        }
        database.close();
    }

    protected void migrateWithHandler(DbMigration migration) {

        try {
            database.execute(migration);
        } catch (MigrationException e) {
            LOGGER.warn(format("Failed to migrate script %s. Reason: %s",
                    migration.getScriptName(), e.getCause().getMessage()));
        }
    }

    protected boolean databaseIsUpToDate() {
        return database.getVersion() >= repository.getLatestVersion();
    }

    public boolean shouldFailGracefully() {
        return shouldFailGracefully;
    }

    public void setShouldFailGracefully(boolean shouldFailGracefully) {
        this.shouldFailGracefully = shouldFailGracefully;
    }

    public Database getDatabase() {
        return database;
    }

    public MigrationRepository getRepository() {
        return repository;
    }

    public boolean isWithConsensus() {
        return withConsensus;
    }
}
