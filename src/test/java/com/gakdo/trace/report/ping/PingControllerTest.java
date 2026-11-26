// 이 파일이 확인하는 것: report-server 점검 주소(/api/v1/reports/ping).

package com.gakdo.trace.report.ping;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import com.gakdo.trace.report.support.WebTestBase;

/**
 * report-server의 ping(점검) 주소 시험. 배포 뒤 "서버가 떴는지, 어떤 버전·커밋이 돌고 있는지" 확인할 때 부르는 주소다.
 * 컨트롤러만 띄우는 가벼운 시험(DB·메시지 큐 없음)이며, 커밋 값은 test-sha로 고정해 응답에 그대로 나오는지 본다.
 */
// (도구) @WebMvcTest = 서버 전체가 아니라 지정한 컨트롤러만 띄워 빠르게 시험한다. properties로 커밋 값을 test-sha로 고정한다
@WebMvcTest(controllers = PingController.class, properties = "app.commit=test-sha")
// WebTestBase를 이어받아 운영과 같은 보안 규칙으로 시험한다
class PingControllerTest extends WebTestBase {

    // (도구) @Autowired = 스프링이 만들어 둔 객체를 이 칸에 넣어 준다
    // (도구) MockMvc = 서버를 실제로 띄우지 않고 HTTP 요청을 흉내 내 컨트롤러를 부르는 도구
    @Autowired
    private MockMvc mockMvc;

    // [상황] 로그인 없이 점검 주소를 부른다.
    // [기대] 200과 서비스 이름 report-server·버전·상태 ok·배포 커밋을 돌려준다.
    //        ※ 응답 코드: 200 = 정상 처리됨
    @Test
    @DisplayName("/api/v1/reports/ping은 토큰 없이 200, 계약 PingResponse(service·version·commit)")
    void ping() throws Exception {
        // (도구) mockMvc.perform(...) = 만든 요청을 컨트롤러에 보내고, 결과(응답)를 확인할 수 있는 객체를 돌려준다
        // 실행: 토큰 없이 점검 주소에 GET을 보낸다
        mockMvc.perform(get("/api/v1/reports/ping"))
                // (도구) andExpect(...) = 응답이 기대와 같은지 확인하고, 다르면 테스트를 실패시킨다
                // 확인: 200(성공)인지 확인한다
                .andExpect(status().isOk())
                // (도구) jsonPath("$.service") = 응답 JSON에서 service 칸을 가리킨다. value(...)로 그 값이 맞는지 본다
                // 서비스 이름이 report-server인지 확인한다
                .andExpect(jsonPath("$.service").value("report-server"))
                // 버전 값이 비어 있지 않은지 확인한다
                .andExpect(jsonPath("$.version").isNotEmpty())
                // 상태가 ok인지 확인한다
                .andExpect(jsonPath("$.status").value("ok"))
                // 고정해 둔 커밋 값이 그대로 나오는지 확인한다(어떤 버전이 떠 있는지 알 수 있게)
                .andExpect(jsonPath("$.commit").value("test-sha"));
    }
}
