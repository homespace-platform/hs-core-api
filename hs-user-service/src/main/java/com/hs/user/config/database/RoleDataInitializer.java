package com.hs.user.config.database;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.hs.user.constant.RoleConstants;
import com.hs.user.model.Role;
import com.hs.user.repository.RoleRepository;

import java.util.Map;

@Component
@Order(1)
@RequiredArgsConstructor
public class RoleDataInitializer implements CommandLineRunner {

    private final RoleRepository roleRepository;

    @Override
    public void run(String... args) {
        Map<String, String> roles = Map.of(
                RoleConstants.ADMIN, "System administrator",
                RoleConstants.USER, "Platform user");

        roles.forEach((name, description) -> {
            if (!roleRepository.existsByName(name)) {
                roleRepository.save(Role.builder()
                        .name(name)
                        .description(description)
                        .build());
            }
        });
    }

}

