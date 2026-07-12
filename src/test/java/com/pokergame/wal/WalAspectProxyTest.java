package com.pokergame.wal;

import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.stereotype.Service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@SpringBootTest(classes = {
        WalAspectProxyTest.TestConfig.class,
        WalAspect.class
})
public class WalAspectProxyTest {

    @MockitoBean
    private WalFileService walFileService;

    @Autowired
    private DummyService dummyService;

    @Test
    void testWalLoggedAspectWithProxy() {
        String result = dummyService.doSomething("room123", "testParam");

        assertEquals("returned-id", result);

        ArgumentCaptor<WalEvent> eventCaptor = ArgumentCaptor.forClass(WalEvent.class);
        verify(walFileService).appendEvent(eq("room123"), eventCaptor.capture());

        WalEvent event = eventCaptor.getValue();
        assertEquals("DummyService", event.serviceName());
        assertEquals("doSomething", event.methodName());
        assertEquals("returned-id", event.returnedId());
        
        assertNotNull(event.parameterTypes());
        assertEquals(2, event.parameterTypes().size());
        assertEquals("java.lang.String", event.parameterTypes().get(0));
        assertEquals("java.lang.String", event.parameterTypes().get(1));
        
        assertNotNull(event.arguments());
        assertEquals(2, event.arguments().size());
        assertEquals("\"room123\"", event.arguments().get(0).toString());
        assertEquals("\"testParam\"", event.arguments().get(1).toString());
    }

    @Configuration
    @EnableAspectJAutoProxy
    static class TestConfig {
        @Bean
        public DummyService dummyService() {
            return new DummyService();
        }

        @Bean
        public JsonMapper jsonMapper() {
            return JsonMapper.builder().build();
        }
    }

    @Service
    public static class DummyService {
        @WalLogged(roomId = "#roomId")
        public String doSomething(String roomId, String param) {
            return "returned-id";
        }
    }
}
