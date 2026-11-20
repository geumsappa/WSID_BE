package com.wsid.content;

import com.wsid.auth.dto.AuthResponses.Success;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/guides")
public class GuideController {
    private final GuideCatalog catalog;

    public GuideController(GuideCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/fraud-types")
    public Success<List<GuideCatalog.FraudTypeSummary>> fraudTypes() {
        return Success.of(catalog.fraudTypes());
    }

    @GetMapping("/fraud-types/{code}")
    public Success<GuideCatalog.FraudType> fraudType(@PathVariable String code) {
        return Success.of(catalog.fraudType(code));
    }

    @GetMapping("/response-cards")
    public Success<List<GuideCatalog.Card>> cards(@RequestParam(defaultValue = "") String codes) {
        var requested = Arrays.stream(codes.split(",")).map(String::strip).filter(code -> !code.isEmpty())
                .collect(Collectors.toSet());
        return Success.of(catalog.cards(requested));
    }

    @GetMapping("/institutions")
    public Success<List<GuideCatalog.Institution>> institutions() {
        return Success.of(catalog.institutions());
    }
}
