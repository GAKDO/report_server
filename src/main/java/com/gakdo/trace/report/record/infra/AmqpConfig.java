package com.gakdo.trace.report.record.infra;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.SimpleMessageConverter;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.amqp.RabbitTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * trace.events에 report-server 큐 trace.report.updates를 붙인다(설계서 소비 큐). 계약대로 등급·진단을 받고,
 * 검증 때 저장소에서 사진을 다시 보려고 촬영 업로드(사진 객체 키)도 받는다(소비자 추가일 뿐 스키마는 그대로, rental-server와 같은 방식).
 * 계약 1.1부터 rental-server의 시점 시작(기기·기종)도 받는다. RP2 진행률을 위해 inspect-worker의 검사 완료도 받는다.
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
    public static final String CHECKPOINT_STARTED = "trace.checkpoint.started";
    /** 시험 렌탈 지우기(rental-server, 11/4) */
    public static final String CHECKPOINT_PURGED = "trace.checkpoint.purged";
    /** RP2 진행률: 검사(정합·탐지) 끝. 스키마는 그대로 두고 소비자만 더한다 */
    public static final String INSPECT_COMPLETED = "trace.inspect.completed";

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
                bind(queue, events, CHECKPOINT_STARTED),
                bind(queue, events, INSPECT_COMPLETED),
                bind(queue, events, CHECKPOINT_PURGED),
                BindingBuilder.bind(dlq).to(dlx).with(DEAD_KEY));
    }

    @Bean
    MessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    /**
     * 리스너는 본문(byte[])을 직접 읽는다. 기본 Jackson 변환기를 리스너에도 쓰면 다른 서버가 붙인 __TypeId__(보낸 쪽 자바 클래스)를
     * 찾다가 ClassNotFound로 메시지를 DLQ에 버린다(11/4 학교 서버에서 확인). 그래서 리스너만 변환하지 않는 변환기로 바꾼다.
     * 발행(RabbitTemplate)은 그대로 JSON이고, 재시도 등 나머지 리스너 설정은 Boot 설정을 그대로 쓴다.
     */
    @Bean
    SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer, ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setMessageConverter(new SimpleMessageConverter());
        return factory;
    }

    @Bean
    RabbitTemplateCustomizer transactedTemplate() {
        return template -> template.setChannelTransacted(true);
    }

    private static Binding bind(Queue queue, TopicExchange exchange, String key) {
        return BindingBuilder.bind(queue).to(exchange).with(key);
    }
}
