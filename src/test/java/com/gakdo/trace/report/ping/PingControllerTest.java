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

@WebMvcTest(controllers = PingController.class, properties = "app.commit=test-sha")
class PingControllerTest extends WebTestBase {

    @Autowired
    private MockMvc mockMvc;

    // [상황] 로그인 없이 점검 주소를 부른다.
    // [기대] 200과 서비스 이름 report-server·버전·상태 ok·배포 커밋을 돌려준다.
    //        ※ 응답 코드: 200 = 정상 처리됨
    @Test
    @DisplayName("/api/v1/reports/ping은 토큰 없이 200, 계약 PingResponse(service·version·commit)")
    void ping() throws Exception {
        mockMvc.perform(get("/api/v1/reports/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("report-server"))
                .andExpect(jsonPath("$.version").isNotEmpty())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.commit").value("test-sha"));
    }
}
