package com.vedicmeet.appserver.config;

import com.mongodb.ReadPreference;
import com.mongodb.WriteConcern;
import org.springframework.boot.autoconfigure.mongo.MongoClientSettingsBuilderCustomizer;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Tunes the shared-MongoDB client for scale and safety on top of whatever the
 * connection URI specifies. Applied even when the URI omits these options.
 *
 * - Read preference PRIMARY is the production Node driver's default and guarantees read-after-write
 *   correctness for auth, wallet, and call state. Read replicas belong behind explicitly read-only
 *   query paths, not as a global service setting.
 * - MAJORITY write concern for durability once write endpoints are migrated.
 * - Bounded connection pool + timeouts so a slow DB degrades gracefully (fail fast)
 *   instead of exhausting threads and cascading into an outage.
 *
 * Pool/timeout sizes are env-overridable so they can differ per environment without
 * a rebuild.
 */
@Configuration
public class MongoConfig {

    /**
     * Money and call-state services use multi-document Mongo transactions. Atlas is a replica set,
     * so transaction status, wallet balance and ledger rows can commit as one unit. The write
     * migration switch remains the outer operational gate.
     */
    @Bean
    public MongoTransactionManager mongoTransactionManager(MongoDatabaseFactory databaseFactory) {
        return new MongoTransactionManager(databaseFactory);
    }

    @Bean
    public MongoClientSettingsBuilderCustomizer mongoClientCustomizer(
            org.springframework.core.env.Environment env) {

        int maxPool = Integer.parseInt(env.getProperty("MONGO_MAX_POOL_SIZE", "100"));
        int minPool = Integer.parseInt(env.getProperty("MONGO_MIN_POOL_SIZE", "5"));
        int connectTimeoutMs = Integer.parseInt(env.getProperty("MONGO_CONNECT_TIMEOUT_MS", "5000"));
        int socketTimeoutMs = Integer.parseInt(env.getProperty("MONGO_SOCKET_TIMEOUT_MS", "20000"));
        int serverSelectTimeoutMs = Integer.parseInt(env.getProperty("MONGO_SERVER_SELECT_TIMEOUT_MS", "5000"));
        int maxWaitMs = Integer.parseInt(env.getProperty("MONGO_POOL_MAX_WAIT_MS", "3000"));
        int maxConnIdleMs = Integer.parseInt(env.getProperty("MONGO_MAX_CONN_IDLE_MS", "60000"));

        return builder -> builder
                .readPreference(ReadPreference.primary())
                .writeConcern(WriteConcern.MAJORITY)
                .retryReads(true)
                .retryWrites(true)
                .applyToConnectionPoolSettings(pool -> pool
                        .maxSize(maxPool)
                        .minSize(minPool)
                        .maxWaitTime(maxWaitMs, TimeUnit.MILLISECONDS)
                        .maxConnectionIdleTime(maxConnIdleMs, TimeUnit.MILLISECONDS))
                .applyToClusterSettings(cluster -> cluster
                        .serverSelectionTimeout(serverSelectTimeoutMs, TimeUnit.MILLISECONDS))
                .applyToSocketSettings(socket -> socket
                        .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
                        .readTimeout(socketTimeoutMs, TimeUnit.MILLISECONDS));
    }
}
