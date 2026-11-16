package com.wsid.content;

import static org.junit.jupiter.api.Assertions.*;

import com.wsid.content.loader.FraudTypeYamlLoader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;

class FraudTypeYamlLoaderTest {
    private static final String YAML = """
            code: SECONDHAND_TRADE
            name: "  중고거래 관련 사기  "
            description: "  테스트용 설명  "
            display_order: 10
            reviewed: true
            source: "https://example.invalid/type"
            checked_at: "2026-11-16"
            """;
    private final FraudTypeYamlLoader loader = new FraudTypeYamlLoader();

    @Test
    void readsUnicodeAndStoresNormalizedDisplayText() {
        var type = loader.load(List.of(resource(YAML))).findAllReviewed().getFirst();
        assertEquals("SECONDHAND_TRADE", type.code());
        assertEquals("중고거래 관련 사기", type.name());
        assertEquals("테스트용 설명", type.description());
        assertEquals(10, type.displayOrder());
        assertEquals("2026-11-16", type.checkedAt().toString());
    }

    static Stream<String> invalidDocuments() {
        Stream<String> roots = Stream.of("", " \n ", "# comment", "null", "[]", "- code: VALUE", "[", "---\n" + YAML + "---\n" + YAML,
                YAML + "code: OTHER\n", YAML + "unexpected: value\n", YAML + "1: value\n", "!!java.lang.Object {}");
        Stream<String> missing = Stream.of("code", "name", "description", "display_order", "reviewed", "source", "checked_at")
                .map(field -> YAML.replaceAll("(?m)^" + field + ":.*\\R", ""));
        Stream<String> nonStrings = Stream.of("code", "name", "description", "source", "checked_at")
                .flatMap(field -> Stream.of("null", "123", "true", "[]", "{}" ).map(value -> field(YAML, field, value)));
        Stream<String> scalars = Stream.of(field(YAML, "reviewed", "\"true\""), field(YAML, "reviewed", "\"false\""),
                field(YAML, "reviewed", "null"), field(YAML, "reviewed", "1"), field(YAML, "display_order", "\"1\""),
                field(YAML, "display_order", "-1"), field(YAML, "display_order", "1.5"), field(YAML, "display_order", "true"),
                field(YAML, "display_order", "null"), field(YAML, "display_order", "2147483648"),
                field(YAML, "checked_at", "2026-11-16"), field(YAML, "checked_at", "\"2026-02-30\""),
                field(YAML, "checked_at", "\"2026-1-1\""), field(YAML, "source", "\"http://example.invalid/type\""),
                field(YAML, "source", "\"/relative\""), field(YAML, "source", "\"https:///path\""),
                field(YAML, "source", "\"https://invalid host/path\""), field(YAML, "code", "\"code\""),
                field(YAML, "code", "\" TYPE \""), field(YAML, "code", "\"TYPE-1\""),
                field(YAML, "code", "\"" + "A".repeat(51) + "\""), field(YAML, "name", "\" \""),
                field(YAML, "name", "\"" + "n".repeat(101) + "\""), field(YAML, "description", "\" \""),
                field(YAML, "description", "\"" + "d".repeat(1001) + "\""));
        return Stream.of(roots, missing, nonStrings, scalars).flatMap(stream -> stream);
    }

    @ParameterizedTest
    @MethodSource("invalidDocuments")
    void rejectsMalformedSchemasAndTypesWithoutRetainingParserBody(String yaml) {
        var error = assertThrows(IllegalStateException.class, () -> loader.load(List.of(resource(yaml))));
        assertNull(error.getCause());
        assertTrue(error.getMessage().startsWith("사기 유형 YAML을 읽거나 검증하지 못했어요: "));
        assertTrue(error.getMessage().contains("test-yaml"));
        assertFalse(error.getMessage().contains("SECONDHAND_TRADE"));
    }

    @Test
    void acceptsExactStringLimitsAndRejectsMalformedUnreviewedDocuments() {
        String exact = field(field(field(YAML, "code", "\"" + "A".repeat(50) + "\""),
                "name", "\"" + "n".repeat(100) + "\""), "description", "\"" + "d".repeat(1000) + "\"");
        assertEquals(50, loader.load(List.of(resource(exact))).findAllReviewed().getFirst().code().length());
        assertThrows(IllegalStateException.class,
                () -> loader.load(List.of(resource(field(field(YAML, "reviewed", "false"), "name", "null")))));
    }

    @Test
    void rejectsMissingResourcesAndDuplicateCodesRegardlessOfReviewStatus() {
        assertThrows(IllegalStateException.class, () -> loader.load(null));
        assertThrows(IllegalStateException.class, () -> loader.load(List.of()));
        assertThrows(IllegalStateException.class, () -> loader.load(Arrays.asList((Resource) null)));
        assertThrows(IllegalArgumentException.class,
                () -> loader.load(List.of(resource(YAML), resource(field(YAML, "reviewed", "false")))));
        assertTrue(loader.load(List.of(resource(field(YAML, "reviewed", "false")))).findAllReviewed().isEmpty());
    }

    @Test
    void usesAndClosesStreamsOnSuccessAndFailure() {
        for (String yaml : List.of(YAML, "[")) {
            var closed = new AtomicBoolean();
            var resource = new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8)) {
                @Override public InputStream getInputStream() throws IOException {
                    return new FilterInputStream(super.getInputStream()) {
                        @Override public void close() throws IOException { closed.set(true); super.close(); }
                    };
                }
            };
            try { loader.load(List.of(resource)); } catch (IllegalStateException ignored) { }
            assertTrue(closed.get());
        }
    }

    @Test
    void resourceIoFailureIsStartupFailureWithoutOriginalCause() {
        var resource = new ByteArrayResource(new byte[0]) {
            @Override public InputStream getInputStream() throws IOException { throw new IOException("private-yaml"); }
        };
        var error = assertThrows(IllegalStateException.class, () -> loader.load(List.of(resource)));
        assertNull(error.getCause());
        assertFalse(error.getMessage().contains("private-yaml"));
    }

    @Test
    void readsJarEntryWithoutConvertingItToFile(@TempDir Path temp) throws IOException {
        Path jar = temp.resolve("types.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("content/fraud-types/type.yml"));
            output.write(YAML.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        var resource = new UrlResource("jar:" + jar.toUri() + "!/content/fraud-types/type.yml");
        assertThrows(IOException.class, resource::getFile);
        assertEquals("SECONDHAND_TRADE", loader.load(List.of(resource)).findAllReviewed().getFirst().code());
    }

    private static String field(String yaml, String field, String rawValue) {
        return yaml.replaceAll("(?m)^" + field + ":.*$", java.util.regex.Matcher.quoteReplacement(field + ": " + rawValue));
    }

    private static Resource resource(String yaml) {
        return new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8), "test-yaml");
    }
}
