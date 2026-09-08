package com.hs.user.config.database;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "homespace.bootstrap")
public record BootstrapAdminProperties(List<BootstrapAdminAccount> admins) {

    public BootstrapAdminProperties {
        admins = admins == null ? List.of() : List.copyOf(admins);
    }

    public List<BootstrapAdminAccount> enabledAdmins() {
        return admins.stream().filter(account -> account != null && account.enabled()).toList();
    }
}
