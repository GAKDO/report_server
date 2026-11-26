// 테스트 도구(테스트 자체는 아님): API 테스트가 운영과 같은 보안 규칙·오류 형식을 쓰게 하는 바탕.

package com.gakdo.trace.report.support;

import org.springframework.context.annotation.Import;

import com.gakdo.trace.report.common.api.ProblemAdvice;
import com.gakdo.trace.report.common.infra.SecurityConfig;

/** WebMvc 시험이 운영과 같은 보안 규칙·오류 형식을 쓰게 한다. */
// (도구) @Import = 이 클래스를 이어받은 테스트에 운영 보안 설정과 오류 응답 설정을 함께 올린다
@Import({SecurityConfig.class, ProblemAdvice.class})
// abstract = 혼자 실행되지 않고, API 테스트들이 이어받아 쓰는 바탕 클래스
public abstract class WebTestBase {
}
