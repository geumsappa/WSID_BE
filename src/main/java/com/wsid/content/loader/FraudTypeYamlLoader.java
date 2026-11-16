package com.wsid.content.loader;

import com.wsid.content.catalog.FraudTypeCatalog;
import com.wsid.content.model.FraudType;
import java.io.IOException;
import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.core.io.Resource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** YAML 자료형을 먼저 검사해 문자열/boolean/숫자의 자동 변환을 허용하지 않아요. */
public final class FraudTypeYamlLoader {
    private static final Set<String> FIELDS =
            Set.of("code", "name", "description", "display_order", "reviewed", "source", "checked_at");

    public FraudTypeCatalog load(List<Resource> resources) {
        if (resources == null || resources.isEmpty()) {
            throw new IllegalStateException("사기 유형 YAML 리소스가 없어요.");
        }
        var types = new ArrayList<FraudType>();
        for (Resource resource : resources) {
            if (resource == null) throw new IllegalStateException("사기 유형 YAML 리소스가 올바르지 않아요.");
            types.add(read(resource));
        }
        return new FraudTypeCatalog(types);
    }

    private FraudType read(Resource resource) {
        try (var input = resource.getInputStream()) {
            var options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            Object loaded = new Yaml(new SafeConstructor(options)).load(input);
            if (!(loaded instanceof Map<?, ?> values) || !values.keySet().equals(FIELDS)
                    || !(values.get("display_order") instanceof Integer order)
                    || !(values.get("reviewed") instanceof Boolean reviewed)) {
                throw new IllegalArgumentException("유형 필드나 타입이 올바르지 않아요.");
            }
            String date = string(values, "checked_at");
            if (!date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
                throw new IllegalArgumentException("유형 확인일 형식이 올바르지 않아요.");
            }
            return new FraudType(string(values, "code"), string(values, "name"), string(values, "description"),
                    order, reviewed, URI.create(string(values, "source")), LocalDate.parse(date));
        } catch (IOException | RuntimeException e) {
            // 파서 예외에 YAML 원문이 들어갈 수 있어 원인 대신 리소스 위치만 남겨요.
            throw new IllegalStateException("사기 유형 YAML을 읽거나 검증하지 못했어요: " + resource.getDescription());
        }
    }

    private String string(Map<?, ?> values, String key) {
        if (!(values.get(key) instanceof String value)) {
            throw new IllegalArgumentException("유형 문자열 필드가 올바르지 않아요.");
        }
        return value;
    }
}
