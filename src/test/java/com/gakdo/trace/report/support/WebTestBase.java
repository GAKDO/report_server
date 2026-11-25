// 테스트 도구(테스트 자체는 아님): API 테스트가 운영과 같은 보안 규칙·오류 형식을 쓰게 하는 바탕.

package com.gakdo.trace.report.support;

import org.springframework.context.annotation.Import;

import com.gakdo.trace.report.common.api.ProblemAdvice;
import com.gakdo.trace.report.common.infra.SecurityConfig;

/** WebMvc 시험이 운영과 같은 보안 규칙·오류 형식을 쓰게 한다. */
@Import({SecurityConfig.class, ProblemAdvice.class})
public abstract class WebTestBase {
}
