package com.hs.user.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.user.config.database.BootstrapAdminProperties;
import com.hs.user.event.consumer.UserSyncConsumer;
import com.hs.user.model.User;
import com.hs.user.notification.AccountNotificationWriter;
import com.hs.user.repository.AddressRepository;
import com.hs.user.repository.RoleRepository;
import com.hs.user.repository.UserRepository;
import com.hs.user.service.KeycloakUserService;
import com.hs.user.service.impl.UserServiceImpl;
import com.hs.user.utils.CurrentUserUtils;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class ChatProfileChangesTest {
    @Test
    void notifiesForAChangedAvatarAfterSchedulingTheSave() {
        var users = mock(UserRepository.class);
        var current = mock(CurrentUserUtils.class);
        var changes = mock(ChatProfileChanges.class);
        var user = new User();
        user.setId("user-a");
        when(current.getCurrentUser()).thenReturn(user);
        var service = new UserServiceImpl(users, mock(RoleRepository.class), mock(AddressRepository.class),
                mock(com.hs.user.repository.KycVerificationRepository.class), mock(KeycloakUserService.class),
                current, mock(AccountNotificationWriter.class), changes);

        service.updateUserAvatar("https://example.test/avatar.jpg", "storage-id");
        var savedBeforeEvent = inOrder(users, changes);
        savedBeforeEvent.verify(users).save(user);
        savedBeforeEvent.verify(changes).afterCommit("user-a");

        service.updateUserAvatar("https://example.test/avatar.jpg", "storage-id");
        verifyNoMoreInteractions(changes);
    }

    @Test
    void keycloakSyncSignalsPublicNameChangesButIgnoresPhoneAndEmail() {
        UserRepository users = mock(UserRepository.class);
        User user = new User();
        user.setId("user-a");
        user.setFirstName("Old");
        when(users.findById("user-a")).thenReturn(Optional.of(user));
        ChatProfileChanges profileChanges = mock(ChatProfileChanges.class);
        var sync = new UserSyncConsumer(users, mock(AddressRepository.class),
                mock(RoleRepository.class), new ObjectMapper(), mock(BootstrapAdminProperties.class), profileChanges);

        sync.userSyncListener("""
                {"action":"UPDATE","userId":"user-a","email":"new@example.test","phoneNumber":"0123456789"}
                """);
        verifyNoInteractions(profileChanges);
        sync.userSyncListener("""
                {"action":"UPDATE","userId":"user-a","firstName":"New"}
                """);
        verify(profileChanges).afterCommit("user-a");
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendsOnlyTheUserIdAfterCommit() throws InterruptedException {
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        var sent = new CountDownLatch(1);
        String topic = "homespace.user.chat-profile.test";
        when(kafka.send(topic, "user-a", "user-a"))
                .thenAnswer(call -> {
                    sent.countDown();
                    return CompletableFuture.completedFuture(null);
                });

        TransactionSynchronizationManager.initSynchronization();
        try {
            new ChatProfileChanges(kafka, topic).afterCommit("user-a");
            verifyNoInteractions(kafka);
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
            assertTrue(sent.await(2, TimeUnit.SECONDS));
            verify(kafka).send(topic, "user-a", "user-a");
            verifyNoMoreInteractions(kafka);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
