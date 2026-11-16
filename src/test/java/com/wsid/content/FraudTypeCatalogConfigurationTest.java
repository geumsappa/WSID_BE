package com.wsid.content;

import static org.junit.jupiter.api.Assertions.*;

import com.wsid.content.catalog.FraudTypeCatalog;
import com.wsid.content.config.ContentProperties;
import com.wsid.content.config.FraudTypeCatalogConfig;
import com.wsid.content.model.FraudType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class FraudTypeCatalogConfigurationTest {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ContentProperties.class)
    static class PropertiesConfiguration {}

    @Test
    void defaultLocationLoadsOnlyUnreviewedMainResourceEvenWithSameCodeTestFixtures() {
        context().run(ctx -> {
            assertNull(ctx.getStartupFailure());
            assertEquals("classpath*:/content/fraud-types/", ctx.getBean(ContentProperties.class).fraudTypesLocation());
            assertTrue(ctx.getBean(FraudTypeCatalog.class).findAllReviewed().isEmpty());
        });
    }

    @Test
    void overrideLoadsBothExtensionsAndDoesNotScanNestedSiblingOrMainDirectories() {
        context().withPropertyValues(location("valid")).run(ctx -> {
            assertNull(ctx.getStartupFailure());
            assertEquals(java.util.List.of("VOICE_PHISHING", "SECONDHAND_TRADE"),
                    ctx.getBean(FraudTypeCatalog.class).findAllReviewed().stream().map(FraudType::code).toList());
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "invalid", "duplicate"})
    void missingMalformedAndDuplicateResourcesFailContextStartup(String folder) {
        context().withPropertyValues(location(folder)).run(ctx -> assertNotNull(ctx.getStartupFailure()));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "https://example.invalid/", "file:/tmp/", "classpath*:/", "classpath*:/content/fraud-types",
            "classpath*:/content/**/", "classpath*:/content/../", "classpath*:/content/*.yml", "classpath*:/content\\types/"})
    void rejectsUnsupportedResourceRoots(String location) {
        assertThrows(IllegalArgumentException.class, () -> new ContentProperties(location));
    }

    @Test
    void acceptsSingleClasspathRootAndRejectsBlankBoundValue() {
        assertDoesNotThrow(() -> new ContentProperties("classpath:/content/fraud-types/"));
        context().withPropertyValues("wsid.content.fraud-types-location=")
                .run(ctx -> assertNotNull(ctx.getStartupFailure()));
    }

    private ApplicationContextRunner context() {
        return new ApplicationContextRunner().withUserConfiguration(PropertiesConfiguration.class, FraudTypeCatalogConfig.class);
    }

    private String location(String folder) {
        return "wsid.content.fraud-types-location=classpath*:/test-content/fraud-types/" + folder + "/";
    }
}
