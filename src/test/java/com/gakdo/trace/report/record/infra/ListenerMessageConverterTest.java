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
    // 테스트 도구: 받은 메시지를 그대로 담아 두는 가짜 리스너
    public static class Recorder {
        // 마지막으로 받은 메시지
        Message received;

        // 메시지를 받으면
        public void onMessage(Message message) {
            // 담아 둔다(테스트가 나중에 꺼내 보려고)
            received = message;
        }
    }

    // [상황] 이 서버에 없는 클래스 이름이 붙은 등급 이벤트가 온다.
    // [기대] 운영 설정의 변환기는 클래스 이름을 무시하고 JSON 그대로 리스너에 넘긴다.
    @Test
    void 다른_서버의_자바_클래스_이름이_붙은_이벤트도_리스너가_받는다() throws Exception {
        // (도구) mock(클래스) = 진짜 일은 안 하는 가짜(대역) 객체. 여기서는 RabbitMQ 연결을 가짜로 넣는다
        // 준비: 운영 설정(AmqpConfig)으로 리스너 공장을 만든다
        SimpleRabbitListenerContainerFactory factory = new AmqpConfig().rabbitListenerContainerFactory(
                new SimpleRabbitListenerContainerFactoryConfigurer(new RabbitProperties()), mock(ConnectionFactory.class));
        // (도구) ReflectionTestUtils.getField = 객체의 숨은(private) 칸 값을 테스트에서 꺼내 보는 도구
        // 공장 안에 설정된 메시지 변환기를 꺼낸다
        MessageConverter converter = (MessageConverter) ReflectionTestUtils.getField(factory, "messageConverter");
        // 받은 메시지를 담을 가짜 리스너를 만든다
        Recorder recorder = new Recorder();

        // 실행: 운영 변환기를 낀 연결 도구로 다른 서버 클래스 이름이 붙은 이벤트를 흘려보낸다
        adapter(recorder, converter).onMessage(foreignEvent(), null);

        // (도구) assertThat(값) = "이 값이 ~여야 한다"를 확인하는 도구. 뒤의 조건이 틀리면 테스트가 실패한다
        // 확인: 리스너가 받은 본문에 rentalId가 들어 있는지 확인한다(JSON 그대로 받음)
        assertThat(new String(recorder.received.getBody(), StandardCharsets.UTF_8)).contains("\"rentalId\"");
    }

    // [상황] 같은 이벤트를 스프링 기본 JSON 변환기로 받아 본다(비교용).
    // [기대] 클래스를 찾다가 실패해 리스너가 받지 못한다. 즉 운영 설정이 꼭 필요하다는 증거.
    @Test
    void 기본_JSON_변환기라면_보낸_쪽_클래스를_찾다가_실패한다() throws Exception {
        // 준비: 받은 메시지를 담을 가짜 리스너를 만든다
        Recorder recorder = new Recorder();
        // 스프링 기본 JSON 변환기를 낀 연결 도구를 만든다(비교용)
        MessagingMessageListenerAdapter adapter = adapter(recorder, new Jackson2JsonMessageConverter(new ObjectMapper()));

        // (도구) assertThatThrownBy(() -> ...) = 괄호 안 코드를 실행했을 때 오류가 나야 한다는 확인
        // 실행+확인: 같은 이벤트를 흘려보내면 "클래스를 찾을 수 없음" 오류가 나는지 확인한다
        assertThatThrownBy(() -> adapter.onMessage(foreignEvent(), null)).hasRootCauseInstanceOf(ClassNotFoundException.class);
        // 리스너가 아무것도 받지 못했는지 확인한다
        assertThat(recorder.received).isNull();
    }

    // 테스트 도구: 가짜 리스너와 변환기를 실제 스프링 리스너 경로(어댑터)로 이어 준다
    private static MessagingMessageListenerAdapter adapter(Recorder recorder, MessageConverter converter) throws Exception {
        // 가짜 리스너의 onMessage 메서드를 찾는다
        Method method = Recorder.class.getMethod("onMessage", Message.class);
        // 리스너 메서드를 부르는 스프링 도구를 만든다
        DefaultMessageHandlerMethodFactory handlers = new DefaultMessageHandlerMethodFactory();
        // 스프링이 하듯 초기화를 마친다
        handlers.afterPropertiesSet();
        // 가짜 리스너와 메서드로 어댑터를 만든다
        MessagingMessageListenerAdapter adapter = new MessagingMessageListenerAdapter(recorder, method);
        // 메시지가 오면 그 메서드를 부르도록 연결한다
        adapter.setHandlerAdapter(new HandlerAdapter(handlers.createInvocableHandlerMethod(recorder, method)));
        // 시험할 변환기를 끼운다
        adapter.setMessageConverter(converter);
        // 다 이은 어댑터를 돌려준다
        return adapter;
    }

    // 테스트 도구: 다른 자바 서버가 보낸 것처럼 꾸민 등급 이벤트를 만든다
    private static Message foreignEvent() {
        // 메시지 머리 정보를 담을 상자를 만든다
        MessageProperties props = new MessageProperties();
        // 본문이 JSON이라고 적는다
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        // 등급 이벤트 경로로 받은 것으로 적는다
        props.setReceivedRoutingKey("trace.checkpoint.graded");
        // 이 서버에 없는 클래스 이름 헤더를 붙인다(문제 상황을 만들려고)
        props.setHeader("__TypeId__", "com.gakdo.trace.other.NotHere");
        // rentalId 하나만 든 JSON 본문으로 메시지를 만든다
        return new Message("{\"rentalId\":\"x\"}".getBytes(StandardCharsets.UTF_8), props);
    }
}
