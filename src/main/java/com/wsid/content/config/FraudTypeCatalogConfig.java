package com.wsid.content.config;

import com.wsid.content.catalog.FraudTypeCatalog;
import com.wsid.content.loader.FraudTypeYamlLoader;
import java.io.IOException;
import java.util.Arrays;
import java.util.stream.Stream;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

@Configuration(proxyBeanMethods = false)
public class FraudTypeCatalogConfig {
    @Bean
    @Lazy(false)
    public FraudTypeCatalog fraudTypeCatalog(ContentProperties properties, ResourceLoader resourceLoader) throws IOException {
        var resolver = new PathMatchingResourcePatternResolver(resourceLoader);
        String location = properties.fraudTypesLocation();
        var resources = Stream.concat(Arrays.stream(resolver.getResources(location + "*.yml")),
                Arrays.stream(resolver.getResources(location + "*.yaml"))).toList();
        return new FraudTypeYamlLoader().load(resources);
    }
}
