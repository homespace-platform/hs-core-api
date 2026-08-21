package com.hs;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

@SpringBootApplication
@ConfigurationPropertiesScan("com.hs")
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
@EnableMethodSecurity
public class HsApiServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(HsApiServiceApplication.class, args);
    }
}
