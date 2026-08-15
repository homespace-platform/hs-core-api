package com.hs.common.persistence;

import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import com.hs.common.context.UserContextHolder;

@Configuration
public class JpaAuditingConfig {
    @Bean
    AuditorAware<String> auditorAware() {
        return () -> Optional.ofNullable(UserContextHolder.get()).map(context -> context.userId());
    }
}
