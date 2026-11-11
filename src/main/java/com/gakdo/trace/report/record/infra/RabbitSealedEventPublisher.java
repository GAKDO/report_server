package com.gakdo.trace.report.record.infra;

import java.util.Map;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import com.gakdo.trace.report.record.application.SealedEventPublisher;

/** 메시지 ID = eventId(소비자가 멱등 처리에 쓴다). 영속 메시지(Spring AMQP 기본). */
@Component
public class RabbitSealedEventPublisher implements SealedEventPublisher {

    private final RabbitTemplate rabbit;

    public RabbitSealedEventPublisher(RabbitTemplate rabbit) {
        this.rabbit = rabbit;
    }

    @Override
    public void publish(String eventId, Map<String, Object> event) {
        rabbit.convertAndSend(AmqpConfig.EXCHANGE, ROUTING_KEY, event, message -> {
            message.getMessageProperties().setMessageId(eventId);
            return message;
        });
    }
}
