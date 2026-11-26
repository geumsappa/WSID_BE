package com.wsid.solution.content;

import com.wsid.content.GuideCatalog;
import com.wsid.solution.domain.CardPriority;
import com.wsid.solution.domain.ResponseCard;
import com.wsid.solution.domain.ResponseCardCatalog;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** GUIDE and SOL share the same reviewed YAML content and internal snapshot versions. */
@Component
public class GuideResponseCardCatalog implements ResponseCardCatalog {
    private final Map<String, ResponseCard> cards;

    public GuideResponseCardCatalog(GuideCatalog guides) {
        cards = guides.cards(Set.of()).stream().collect(Collectors.toUnmodifiableMap(
                GuideCatalog.Card::code, card -> new ResponseCard(card.code(), guides.cardVersion(card.code()),
                        CardPriority.valueOf(card.priority()), card.title(), card.institutionCodes())));
    }

    @Override
    public Optional<ResponseCard> findReviewed(String code) {
        return code == null ? Optional.empty() : Optional.ofNullable(cards.get(code));
    }
}
