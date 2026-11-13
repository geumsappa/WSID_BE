package com.wsid.training.dto;

import com.wsid.training.domain.DiscoveryStatus;
import com.wsid.training.domain.TrainingPhase;
import java.util.List;

public record DiscoveryResponse(long trainingId, int stateVersion, TrainingPhase phase,
        DiscoveryStatus discoveryStatus, int questionCount, boolean fallback, List<MessageDto> messages) {}
