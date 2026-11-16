package com.hs.user.config.database;

import com.hs.user.constant.RoleConstants;
import com.hs.user.model.Role;
import com.hs.user.model.User;
import com.hs.user.repository.RoleRepository;
import com.hs.user.repository.UserRepository;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.UsersResource;
import org.keycloak.representations.idm.UserRepresentation;
import org.mockito.ArgumentCaptor;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AdminUserDataInitializerTest {
    @Test void createsKeycloakAndCoreAdminWithoutReservingCitizenId() {
        var users = mock(UserRepository.class);
        var roles = mock(RoleRepository.class);
        var realm = mock(RealmResource.class);
        var keycloakUsers = mock(UsersResource.class);
        var response = mock(Response.class);
        var role = new Role();role.setName(RoleConstants.ADMIN);
        when(roles.findByName(RoleConstants.ADMIN)).thenReturn(Optional.of(role));
        when(realm.users()).thenReturn(keycloakUsers);
        when(keycloakUsers.searchByUsername("homespace", true)).thenReturn(List.of());
        when(keycloakUsers.searchByEmail("admin@example.test", true)).thenReturn(List.of());
        when(keycloakUsers.create(any(UserRepresentation.class))).thenReturn(response);
        when(response.getStatusInfo()).thenReturn(Response.Status.CREATED);
        when(response.getLocation()).thenReturn(URI.create("https://keycloak.test/admin/realms/test/users/keycloak-id"));

        var account = new BootstrapAdminAccount(true,"homespace","admin@example.test","temporary-password",
                null,"System","Administrator");
        new AdminUserDataInitializer(users, roles, realm, new BootstrapAdminProperties(List.of(account))).run();

        var saved = ArgumentCaptor.forClass(User.class);
        verify(users).save(saved.capture());
        assertEquals("keycloak-id", saved.getValue().getId());
        assertEquals(RoleConstants.ADMIN, saved.getValue().getRole().getName());
        assertNull(saved.getValue().getCccd());
        verify(users, never()).existsByCccdAndIdNot(any(), any());
    }

    @Test void existingAdminKeepsItsCitizenId() {
        var users = mock(UserRepository.class);
        var roles = mock(RoleRepository.class);
        var realm = mock(RealmResource.class);
        var keycloakUsers = mock(UsersResource.class);
        var representation = new UserRepresentation();representation.setId("keycloak-id");
        var role = new Role();role.setName(RoleConstants.ADMIN);
        var existing = new User();existing.setId("keycloak-id");existing.setRole(role);existing.setCccd("001234567890");
        when(roles.findByName(RoleConstants.ADMIN)).thenReturn(Optional.of(role));
        when(realm.users()).thenReturn(keycloakUsers);
        when(keycloakUsers.searchByUsername("homespace", true)).thenReturn(List.of(representation));
        when(users.existsById("keycloak-id")).thenReturn(true);
        when(users.findById("keycloak-id")).thenReturn(Optional.of(existing));

        var account = new BootstrapAdminAccount(true,"homespace","admin@example.test","temporary-password",
                null,"System","Administrator");
        new AdminUserDataInitializer(users, roles, realm, new BootstrapAdminProperties(List.of(account))).run();

        assertEquals("001234567890", existing.getCccd());
        verify(users, never()).save(any());
        verify(users, never()).saveAndFlush(any());
    }
}
