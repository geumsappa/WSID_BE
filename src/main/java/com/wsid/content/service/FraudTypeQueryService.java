package com.wsid.content.service;

import com.wsid.content.catalog.FraudTypeCatalog;
import com.wsid.content.dto.FraudTypeSummaryResponse;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class FraudTypeQueryService {
    private final FraudTypeCatalog catalog;

    public FraudTypeQueryService(FraudTypeCatalog catalog) {
        this.catalog = catalog;
    }

    public List<FraudTypeSummaryResponse> findAll() {
        return catalog.findAllReviewed().stream().map(FraudTypeSummaryResponse::from).toList();
    }
}
