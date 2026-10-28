package com.hs.listing.nearby;

import com.hs.listing.nearby.model.NearbyCategory;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;

/** Internal reference groups only. Never calls a provider or seeds external POIs. */
@Component
@Order(30)
@RequiredArgsConstructor
public class NearbyCategoryInitializer implements CommandLineRunner {
    private final NearbyCategoryRepository repository;
    private static final List<String> LABELS = List.of(
        "Giáo dục", "Y tế", "Mua sắm", "Ăn uống", "Ngân hàng / ATM", "Đi lại", "Giải trí", "Thể thao");

    @Override
    @Transactional
    public void run(String... args) {
        var existing = new HashSet<>(repository.findAll().stream().map(NearbyCategory::getCode).toList());
        var missing = new ArrayList<NearbyCategory>();
        var groups = NearbyTypes.Group.values();
        for (int i = 0; i < groups.length; i++) {
            if (existing.contains(groups[i].name())) continue;
            NearbyCategory category = new NearbyCategory();
            category.setCode(groups[i].name());
            category.setDisplayNameVi(LABELS.get(i));
            category.setDisplayOrder(i + 1);
            missing.add(category);
        }
        repository.saveAll(missing);
    }
}
