package com.wsid.content;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.wsid.common.config.SecurityConfig;
import com.wsid.content.config.ContentProperties;
import com.wsid.content.config.FraudTypeCatalogConfig;
import com.wsid.content.controller.GuideController;
import com.wsid.content.service.FraudTypeQueryService;
import jakarta.servlet.Filter;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

class GuideSecurityTest {
    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    @EnableConfigurationProperties(ContentProperties.class)
    @Import({SecurityConfig.class, FraudTypeCatalogConfig.class, FraudTypeQueryService.class,
            GuideController.class, ProtectedController.class})
    static class WebConfiguration {}

    @RestController
    static class ProtectedController {
        final AtomicInteger calls = new AtomicInteger();
        @GetMapping({"/api/private-probe", "/api/guides/fraud-types/SECONDHAND_TRADE"})
        String protectedEndpoint() { calls.incrementAndGet(); return "protected"; }
    }

    @Test
    void anonymousGetWorksThroughRealSecurityChainWhileOtherPathsRemainBlocked() throws Exception {
        try (var ctx = context("classpath*:/test-content/fraud-types/valid/")) {
            var mvc = MockMvcBuilders.webAppContextSetup(ctx)
                    .addFilters(ctx.getBean("springSecurityFilterChain", Filter.class)).build();
            mvc.perform(get("/api/guides/fraud-types")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data.length()").value(2))
                    .andExpect(jsonPath("$.data[0].code").value("VOICE_PHISHING"));
            mvc.perform(get("/api/private-probe")).andExpect(status().is4xxClientError());
            mvc.perform(get("/api/guides/fraud-types/SECONDHAND_TRADE")).andExpect(status().is4xxClientError());
            assertEquals(0, ctx.getBean(ProtectedController.class).calls.get());
            int status = mvc.perform(post("/api/guides/fraud-types")).andReturn().getResponse().getStatus();
            assertTrue(status == 401 || status == 403, "미지원 POST도 보안 필터에서 차단되어야 해요.");
        }
    }

    @Test
    void defaultUnreviewedMainCatalogIsPublicEmptyArrayWithoutAnyDatabase() throws Exception {
        try (var ctx = context("classpath*:/content/fraud-types/")) {
            var mvc = MockMvcBuilders.webAppContextSetup(ctx)
                    .addFilters(ctx.getBean("springSecurityFilterChain", Filter.class)).build();
            mvc.perform(get("/api/guides/fraud-types")).andExpect(status().isOk())
                    .andExpect(content().json("{\"success\":true,\"data\":[]}"));
        }
    }

    private AnnotationConfigWebApplicationContext context(String location) {
        var ctx = new AnnotationConfigWebApplicationContext();
        ctx.setServletContext(new MockServletContext());
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(ctx, "wsid.content.fraud-types-location=" + location);
        ctx.register(WebConfiguration.class);
        ctx.refresh();
        return ctx;
    }
}
