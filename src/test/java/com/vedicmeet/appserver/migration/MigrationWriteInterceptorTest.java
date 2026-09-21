package com.vedicmeet.appserver.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.method.HandlerMethod;

import static org.junit.jupiter.api.Assertions.*;

class MigrationWriteInterceptorTest {

    @Test
    void writeIsBlockedByDefaultGateUsingHttp200Envelope() throws Exception {
        MigrationWriteInterceptor gate = new MigrationWriteInterceptor(false, new ObjectMapper());
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = gate.preHandle(new MockHttpServletRequest(), response, handler("write"));

        assertFalse(allowed);
        assertEquals(200, response.getStatus());
        assertEquals("disabled", response.getHeader("X-VedicMeet-Migration-Write"));
        assertTrue(response.getContentAsString().contains("Migration write route is disabled"));
    }

    @Test
    void readsRemainAvailableAndExplicitEnableAllowsWrite() throws Exception {
        assertTrue(new MigrationWriteInterceptor(false, new ObjectMapper())
                .preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), handler("read")));
        assertTrue(new MigrationWriteInterceptor(true, new ObjectMapper())
                .preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), handler("write")));
    }

    private HandlerMethod handler(String name) throws NoSuchMethodException {
        return new HandlerMethod(new Probe(), Probe.class.getMethod(name));
    }

    static class Probe {
        @GetMapping public void read() { }
        @GetMapping @MigrationWrite public void write() { }
    }
}
