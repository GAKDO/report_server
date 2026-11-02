package com.gakdo.trace.report.record.infra;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.amqp.RabbitTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * trace.events에 report-server 큐 trace.report.updates를 붙인다(설계서 소비 큐). 계약대로 등급·진단을 받고,
 * 검증 때 저장소에서 사진을 다시 보려고 촬영 업로드(사진 객체 키)도 받는다(소비자 추가일 뿐 스키마는 그대로, rental-server와 같은 방식).
 * 계속 실패한 메시지는 trace.dlx(topic)에 dead-letter 키 trace.report.updates.dead로 보낸다.
 * 채널 트랜잭션을 켜서 봉인 이벤트가 DB 커밋과 함께 나가게 한다.
 */
@Configuration
public class AmqpConfig {

    public static final String EXCHANGE = "trace.events";
    public static final String QUEUE = "trace.report.updates";
    public static final String DLX = "trace.dlx";
    public static final String DEAD_KEY = "trace.report.updates.dead";
    public static final String DLQ = "trace.report.updates.dlq";

    public static final String CAPTURE_UPLOADED = "trace.capture.uploaded";
    public static final String CHECKPOINT_GRADED = "trace.checkpoint.graded";
    public static final String DIAGNOSTICS_RECORDED = "trace.diagnostics.recorded";

    @Bean
    Declarables reportTopology() {
        TopicExchange events = new TopicExchange(EXCHANGE, true, false);
        TopicExchange dlx = new TopicExchange(DLX, true, false);
        Queue queue = QueueBuilder.durable(QUEUE).deadLetterExchange(DLX).deadLetterRoutingKey(DEAD_KEY).build();
        Queue dlq = QueueBuilder.durable(DLQ).build();
        return new Declarables(events, dlx, queue, dlq,
                bind(queue, events, CAPTURE_UPLOADED),
                bind(queue, events, CHECKPOINT_GRADED),
                bind(queue, events, DIAGNOSTICS_RECORDED),
                BindingBuilder.bind(dlq).to(dlx).with(DEAD_KEY));
    }

    @Bean
    MessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    @Bean
    RabbitTemplateCustomizer transactedTemplate() {
        return template -> template.setChannelTransacted(true);
    }

    private static Binding bind(Queue queue, TopicExchange exchange, String key) {
        return BindingBuilder.bind(queue).to(exchange).with(key);
    }
}
