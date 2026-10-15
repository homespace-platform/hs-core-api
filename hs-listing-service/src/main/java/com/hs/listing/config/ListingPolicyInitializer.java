package com.hs.listing.config;
import com.hs.listing.repository.ListingPolicyDefinitionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
@Component @Order(21) @RequiredArgsConstructor
public class ListingPolicyInitializer implements CommandLineRunner {
    private final ListingPolicyDefinitionRepository repository;
    @Override @Transactional public void run(String... args) {
        // Seed missing definitions only; do not overwrite operator edits on each restart.
        for (var d : ListingPolicyCatalog.defaults()) if (!repository.existsById(d.getCode())) repository.save(d);
    }
}
