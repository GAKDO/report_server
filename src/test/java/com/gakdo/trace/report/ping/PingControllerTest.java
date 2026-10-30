package com.gakdo.trace.report.ping;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PingController.class)
class PingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void ping() throws Exception {
        mockMvc.perform(get("/api/v1/reports/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("report-server"))
                .andExpect(jsonPath("$.status").value("ok"));
    }
}
