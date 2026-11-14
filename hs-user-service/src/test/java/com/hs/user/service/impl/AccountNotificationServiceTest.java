package com.hs.user.service.impl;

import com.hs.user.dto.request.AdminUpdateUserRequest;
import com.hs.user.model.Role;
import com.hs.user.model.User;
import com.hs.user.notification.AccountNotificationWriter;
import com.hs.user.repository.*;
import com.hs.user.service.KeycloakUserService;
import com.hs.user.utils.CurrentUserUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.Optional;
import static com.hs.user.notification.AccountNotificationWriter.Event.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AccountNotificationServiceTest {
    final UserRepository users=mock(UserRepository.class);
    final RoleRepository roles=mock(RoleRepository.class);
    final KeycloakUserService keycloak=mock(KeycloakUserService.class);
    final CurrentUserUtils identity=mock(CurrentUserUtils.class);
    final AccountNotificationWriter notices=mock(AccountNotificationWriter.class);
    final UserServiceImpl service=new UserServiceImpl(users,roles,mock(AddressRepository.class),mock(KycVerificationRepository.class),keycloak,identity,notices);
    User target;
    @BeforeEach void setup() {
        target=new User();target.setId("target");target.setEmail("private@secret.test");target.setUsername("target");target.setActive(true);
        target.setRole(Role.builder().id("old-role").name("OLD_PRIVATE_ROLE").build());
        when(identity.getCurrentUserId()).thenReturn("verified-admin");
        when(users.findByIdForUpdate("target")).thenReturn(Optional.of(target));
    }
    AdminUpdateUserRequest update(String roleId) { return new AdminUpdateUserRequest(null,null,null,null,null,null,null,roleId); }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void changedStatusEnqueuesOnlyAfterKeycloakReturns(boolean enabled) {
        target.setActive(!enabled);service.updateUserStatus("target",enabled);
        var order=inOrder(users,keycloak,notices);
        order.verify(users).findByIdForUpdate("target");order.verify(users).save(target);
        order.verify(keycloak).updateUserIfChanged(eq("target"),argThat(r->r.enabled()==enabled));
        order.verify(notices).enqueue(target,"verified-admin",enabled?ACCOUNT_ENABLED:ACCOUNT_DISABLED);
        assertEquals(enabled,target.getActive());verifyNoMoreInteractions(notices);
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void sameStatusStillReconcilesKeycloakButDoesNotNotify(boolean enabled) {
        target.setActive(enabled);service.updateUserStatus("target",enabled);
        verify(keycloak).updateUserIfChanged(eq("target"),argThat(r->r.enabled()==enabled));verifyNoInteractions(notices);
    }
    @Test void failedKeycloakCallDoesNotReachWriter() {
        doThrow(new IllegalStateException("test unavailable")).when(keycloak).updateUserIfChanged(anyString(),any());
        assertThrows(IllegalStateException.class,()->service.updateUserStatus("target",false));verifyNoInteractions(notices);
    }
    @Test void selfDisableAndUnknownTargetRemainDenied() {
        when(identity.getCurrentUserId()).thenReturn("target");
        assertThrows(RuntimeException.class,()->service.updateUserStatus("target",false));
        assertThrows(RuntimeException.class,()->service.updateUserStatus("missing",true));verifyNoInteractions(keycloak,notices);
    }
    @Test void actualRoleChangeNotifiesOnlyTheAffectedAccount() {
        var next=Role.builder().id("new-role").name("NEW_PRIVATE_ROLE").build();
        when(roles.findByIdAndActiveTrue("new-role")).thenReturn(Optional.of(next));
        service.updateUser("target",update("new-role"));
        assertSame(next,target.getRole());verify(notices).enqueue(target,"verified-admin",ACCOUNT_ROLE_CHANGED);verifyNoMoreInteractions(notices);
    }
    @Test void sameRoleBlankRoleAndProfileOnlyDoNotNotify() {
        when(roles.findByIdAndActiveTrue("old-role")).thenReturn(Optional.of(target.getRole()));
        for(String roleId:new String[]{"old-role",null,""," "}) service.updateUser("target",update(roleId));
        service.updateUser("target",new AdminUpdateUserRequest(null,null,"New name",null,null,null,null,null));
        verifyNoInteractions(notices);
    }
    @Test void ownRoleUnknownRoleAndFailedProfileSyncDoNotNotify() {
        when(identity.getCurrentUserId()).thenReturn("target");
        assertThrows(RuntimeException.class,()->service.updateUser("target",update("new-role")));
        when(identity.getCurrentUserId()).thenReturn("verified-admin");
        assertThrows(RuntimeException.class,()->service.updateUser("target",update("missing-role")));
        doThrow(new IllegalStateException("test sync failure")).when(keycloak).updateUserIfChanged(anyString(),any());
        assertThrows(IllegalStateException.class,()->service.updateUser("target",update("new-role")));verifyNoInteractions(notices);
    }
}
