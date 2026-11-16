package com.wsid.content;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.wsid.common.api.ApiResponse;
import com.wsid.content.catalog.FraudTypeCatalog;
import com.wsid.content.controller.GuideController;
import com.wsid.content.loader.FraudTypeYamlLoader;
import com.wsid.content.service.FraudTypeQueryService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class GuideControllerTest {
    @Test
    void returnsExactSuccessEnvelopeAndPublicFieldsAsArray() throws Exception {
        var resolver = new PathMatchingResourcePatternResolver();
        var catalog = new FraudTypeYamlLoader().load(List.of(
                resolver.getResource("classpath:/test-content/fraud-types/valid/secondhand.yml")));
        var mvc = MockMvcBuilders.standaloneSetup(new GuideController(new FraudTypeQueryService(catalog))).build();
        mvc.perform(get("/api/guides/fraud-types"))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {"success":true,"data":[{"code":"SECONDHAND_TRADE","name":"중고거래 관련 사기",
                        "description":"테스트용 중고거래 유형 설명"}]}""", JsonCompareMode.STRICT))
                .andExpect(jsonPath("$.data").isArray()).andExpect(jsonPath("$.data[0].reviewed").doesNotExist())
                .andExpect(jsonPath("$.data[0].source").doesNotExist());
    }

    @Test
    void emptyListIs200WithExplicitArrayAndNoRequestParametersRequired() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new GuideController(new FraudTypeQueryService(new FraudTypeCatalog(List.of())))).build();
        mvc.perform(get("/api/guides/fraud-types")).andExpect(status().isOk())
                .andExpect(content().json("{\"success\":true,\"data\":[]}", JsonCompareMode.STRICT));
        mvc.perform(post("/api/guides/fraud-types")).andExpect(status().isMethodNotAllowed());
    }

    @Test
    void successEnvelopeRequiresActualSuccessAndNonNullData() {
        assertThrows(IllegalArgumentException.class, () -> ApiResponse.ok(null));
        assertThrows(IllegalArgumentException.class, () -> new ApiResponse<>(false, List.of()));
        assertTrue(ApiResponse.ok(List.of()).success());
    }
}
