package com.hs.api.controller.storage;

import com.hs.common.context.UserContext;
import com.hs.common.context.UserContextHolder;
import com.hs.storage.dto.request.CreateUploadRequest;
import com.hs.storage.model.constant.StoragePurpose;
import com.hs.storage.service.StorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class StorageControllerTemplateUploadTest {
    final StorageService service = mock(StorageService.class);
    final StorageController controller = new StorageController(service);

    @AfterEach void clear() { UserContextHolder.clear(); SecurityContextHolder.clearContext(); }

    private void login(String... authorities) {
        UserContextHolder.set(new UserContext("actor", null));
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "actor", "n/a", Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()));
    }

    private static CreateUploadRequest templateUpload() {
        return new CreateUploadRequest("t.docx", "application/octet-stream", 10L,
                StoragePurpose.CONTRACT_DOCUMENT, null, "CONTRACT_TEMPLATE", null);
    }

    @Test void contractTemplateUploadNeedsAdminRoleAndManagePermission() {
        login("ADMIN");
        assertThrows(AccessDeniedException.class, () -> controller.createUpload(templateUpload()));
        login("USER", "CONTRACT_TEMPLATE_MANAGE");
        assertThrows(AccessDeniedException.class, () -> controller.createUpload(templateUpload()));
        verifyNoInteractions(service);
        login("ADMIN", "CONTRACT_TEMPLATE_MANAGE");
        controller.createUpload(templateUpload());
        verify(service).createUpload(any());
    }
}
