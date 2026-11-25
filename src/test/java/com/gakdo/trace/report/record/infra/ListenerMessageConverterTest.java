// 이 파일이 확인하는 것: 다른 자바 서버가 보낸 이벤트를 메시지 변환 문제 없이 받는지.
// 자바 서버는 이벤트에 "보낸 쪽 클래스 이름" 헤더를 붙이는데, report-server에는 그 클래스가 없다.

package com.gakdo.trace.report.record.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.adapter.HandlerAdapter;
import org.springframework.amqp.rabbit.listener.adapter.MessagingMessageListenerAdapter;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.messaging.handler.annotation.support.DefaultMessageHandlerMethodFactory;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

/** 다른 서버(자바 생산자)가 __TypeId__ 헤더를 붙여 보낸 이벤트를 리스너가 실제 Spring 경로로 받는지 본다. */
class ListenerMessageConverterTest {

    /** 리스너와 같은 모양(Message를 그대로 받음) */
    public static class Recorder {
        Message received;

        public void onMessage(Message message) {
            received = message;
        }
    }

    // [상황] 이 서버에 없는 클래스 이름이 붙은 등급 이벤트가 온다.
    // [기대] 운영 설정의 변환기는 클래스 이름을 무시하고 JSON 그대로 리스너에 넘긴다.
    @Test
    void 다른_서버의_자바_클래스_이름이_붙은_이벤트도_리스너가_받는다() throws Exception {
        SimpleRabbitListenerContainerFactory factory = new AmqpConfig().rabbitListenerContainerFactory(
                new SimpleRabbitListenerContainerFactoryConfigurer(new RabbitProperties()), mock(ConnectionFactory.class));
        MessageConverter converter = (MessageConverter) ReflectionTestUtils.getField(factory, "messageConverter");
        Recorder recorder = new Recorder();

        adapter(recorder, converter).onMessage(foreignEvent(), null);

        assertThat(new String(recorder.received.getBody(), StandardCharsets.UTF_8)).contains("\"rentalId\"");
    }

    // [상황] 같은 이벤트를 스프링 기본 JSON 변환기로 받아 본다(비교용).
    // [기대] 클래스를 찾다가 실패해 리스너가 받지 못한다. 즉 운영 설정이 꼭 필요하다는 증거.
    @Test
    void 기본_JSON_변환기라면_보낸_쪽_클래스를_찾다가_실패한다() throws Exception {
        Recorder recorder = new Recorder();
        MessagingMessageListenerAdapter adapter = adapter(recorder, new Jackson2JsonMessageConverter(new ObjectMapper()));

        assertThatThrownBy(() -> adapter.onMessage(foreignEvent(), null)).hasRootCauseInstanceOf(ClassNotFoundException.class);
        assertThat(recorder.received).isNull();
    }

    private static MessagingMessageListenerAdapter adapter(Recorder recorder, MessageConverter converter) throws Exception {
        Method method = Recorder.class.getMethod("onMessage", Message.class);
        DefaultMessageHandlerMethodFactory handlers = new DefaultMessageHandlerMethodFactory();
        handlers.afterPropertiesSet();
        MessagingMessageListenerAdapter adapter = new MessagingMessageListenerAdapter(recorder, method);
        adapter.setHandlerAdapter(new HandlerAdapter(handlers.createInvocableHandlerMethod(recorder, method)));
        adapter.setMessageConverter(converter);
        return adapter;
    }

    private static Message foreignEvent() {
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setReceivedRoutingKey("trace.checkpoint.graded");
        props.setHeader("__TypeId__", "com.gakdo.trace.other.NotHere");
        return new Message("{\"rentalId\":\"x\"}".getBytes(StandardCharsets.UTF_8), props);
    }
}
