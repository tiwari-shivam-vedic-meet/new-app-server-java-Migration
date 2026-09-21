package com.vedicmeet.appserver.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.bson.types.ObjectId;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

/**
 * Makes Jackson serialize Mongo documents the way Node/Express does, so ported read
 * endpoints are byte-identical:
 *
 *   - ObjectId -> its hex string (Node's ObjectId.toJSON() returns the hex string).
 *     Without this, Jackson would emit an object or fail, breaking every response
 *     that returns _id.
 *   - Dates as ISO-8601 strings, not epoch millis (Node emits Date.toISOString()).
 *
 * org.bson.Document already implements Map, so Jackson serializes it as a JSON
 * object with no extra config.
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer mongoJsonCustomizer() {
        SimpleModule module = new SimpleModule();
        module.addSerializer(ObjectId.class, new JsonSerializer<ObjectId>() {
            @Override
            public void serialize(ObjectId value, JsonGenerator gen, SerializerProvider s) throws IOException {
                gen.writeString(value.toHexString());
            }
        });
        return builder -> {
            builder.modules(module);
            builder.featuresToDisable(
                    com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            // Match Node's Date.toISOString() exactly (e.g. 2024-01-02T03:04:05.678Z),
            // not Jackson's default "+00:00" offset, so response diffs are meaningful.
            builder.simpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
            builder.timeZone(java.util.TimeZone.getTimeZone("UTC"));
        };
    }
}
