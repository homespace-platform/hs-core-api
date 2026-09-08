package com.hs.user.repository;

import com.hs.user.model.KycWebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KycWebhookEventRepository extends JpaRepository<KycWebhookEvent, String> {
}
