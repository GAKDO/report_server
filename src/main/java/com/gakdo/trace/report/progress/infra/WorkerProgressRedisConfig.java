package com.gakdo.trace.report.progress.infra;

import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gakdo.trace.report.progress.application.WorkerProgress;

/**
 * Redis 채널 {prefix}progress:{rentalId}를 구독한다(ml runner.RedisProgress와 같은 이름).
 * 진행률은 보조라 메시지가 깨지거나 Redis가 끊겨도 경고만 남기고 이벤트 단계로 계속 간다.
 */
@Configuration
@ConditionalOnProperty(name = "app.progress.redis.enabled", havingValue = "true")
public class WorkerProgressRedisConfig {

    private static final Logger log = LoggerFactory.getLogger(WorkerProgressRedisConfig.class);

    /** worker 메시지는 계약 밖이라 모르는 필드는 무시한다(전역 설정은 모르는 필드를 막음). */
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Bean
    RedisMessageListenerContainer workerProgressContainer(RedisConnectionFactory connections, WorkerProgress progress,
            @Value("${app.progress.redis.prefix:trace:}") String prefix) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connections);
        container.addMessageListener(listener(progress, prefix), new PatternTopic(prefix + "progress:*"));
        return container;
    }

    static MessageListener listener(WorkerProgress progress, String prefix) {
        String channelPrefix = prefix + "progress:";
        return (Message message, byte[] pattern) -> {
            String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
            if (!channel.startsWith(channelPrefix)) {
                return;
            }
            String rentalId = channel.substring(channelPrefix.length());
            try {
                progress.onWorker(rentalId, JSON.readValue(message.getBody(), WorkerProgress.Message.class));
            } catch (Exception e) { // 진행률 실패로 다른 메시지 처리를 막지 않는다
                log.warn("worker 진행 메시지를 처리하지 못했습니다 channel={}: {}", channel, e.toString());
            }
        };
    }
}
