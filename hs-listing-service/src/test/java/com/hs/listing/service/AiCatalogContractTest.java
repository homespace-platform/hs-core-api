package com.hs.listing.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.hs.listing.nearby.NearbyKinds;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The AI service fakes Core with a catalog fixture. Writing it from the seeded Core catalog keeps the
 * fixture's field codes, operators and enums identical to what Core compiles. Opt-in:
 * {@code -Dhs.ai.catalog-fixture=<path>}; without it only the shape is checked.
 */
class AiCatalogContractTest {

    @Test
    void catalogViewIsVersionedAndCanBeExportedForTheAiFixture() throws Exception {
        List<Map<String, String>> kinds = NearbyKinds.available(Set.of("7372", "7377", "7321", "7326", "7332005", "9361009",
                "7315", "7328", "9942002", "9362")).stream().map(k -> {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("code", k.code());
            item.put("group", k.group().name());
            item.put("label", k.label());
            if (k.note() != null) item.put("note", k.note());
            return item;
        }).toList();
        AiListingFieldCatalog.CatalogView view = AiListingFieldCatalog.seeded().view(kinds);
        assertEquals(2, view.version());
        assertTrue(view.catalogVersion().startsWith("fields-v2-"));
        assertEquals(10, view.nearbyKinds().size());

        String target = System.getProperty("hs.ai.catalog-fixture");
        if (target != null && !target.isBlank()) {
            Path path = Path.of(target);
            Files.createDirectories(path.getParent());
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(path.toFile(), view);
        }
    }
}
