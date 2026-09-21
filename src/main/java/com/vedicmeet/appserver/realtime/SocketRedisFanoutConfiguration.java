package com.vedicmeet.appserver.realtime;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.nio.charset.StandardCharsets;

/**
 * Subscribes to the exact {@code socket:io:emit} bridge used by Node socket-emitter.js. This lets
 * Node workers and peer Java instances deliver events to clients connected to this Java instance.
 */
@Configuration
@ConditionalOnProperty(prefix = "vedicmeet.socket", name = "redis-fanout-enabled", havingValue = "true")
public class SocketRedisFanoutConfiguration {

    @Bean
    RedisMessageListenerContainer socketRedisFanoutContainer(
            RedisConnectionFactory connectionFactory, SocketEventPublisher publisher) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener((message, pattern) -> publisher.acceptFanout(
                        new String(message.getBody(), StandardCharsets.UTF_8)),
                new ChannelTopic(SocketEventPublisher.CHANNEL));
        return container;
    }
}
