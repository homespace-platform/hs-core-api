package com.hs.api.controller;

import com.hs.api.advice.handler.GlobalException;
import com.hs.api.controller.listing.FavoriteController;
import com.hs.common.context.UserContext;
import com.hs.common.context.UserContextHolder;
import com.hs.common.security.Permissions;
import com.hs.listing.service.ListingFavoriteService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/** User-scope RBAC: each user-facing mutation handler carries its toggleable permission; denial is a 403 envelope. */
class UserScopePermissionSecurityTest {

    private static final Map<String, String> EXPECTED = Map.ofEntries(
            Map.entry("ListingController.upsert", Permissions.LISTING_POST),
            Map.entry("ListingController.changeStatus", Permissions.LISTING_POST),
            Map.entry("ListingController.hide", Permissions.LISTING_POST),
            Map.entry("PropertyBranchController.createBranch", Permissions.LISTING_POST),
            Map.entry("PropertyBranchController.updateBranch", Permissions.LISTING_POST),
            Map.entry("PropertyBranchController.deleteBranch", Permissions.LISTING_POST),
            Map.entry("BranchLayoutController.saveLayout", Permissions.LISTING_POST),
            Map.entry("BranchAssignmentsController.linkListing", Permissions.LISTING_POST),
            Map.entry("BranchAssignmentsController.unlinkListing", Permissions.LISTING_POST),
            Map.entry("BranchAssignmentsController.attachContract", Permissions.LISTING_POST),
            Map.entry("BranchAssignmentsController.detachContract", Permissions.LISTING_POST),
            Map.entry("BranchAssignmentsController.setManualStatus", Permissions.LISTING_POST),
            Map.entry("ViewingAppointmentController.createAppointment", Permissions.APPOINTMENT_BOOK),
            Map.entry("ViewingAppointmentController.requestReschedule", Permissions.APPOINTMENT_BOOK),
            Map.entry("ViewingAppointmentController.approveAppointment", Permissions.APPOINTMENT_MANAGE),
            Map.entry("ViewingAppointmentController.rejectAppointment", Permissions.APPOINTMENT_MANAGE),
            Map.entry("ViewingAppointmentController.approveReschedule", Permissions.APPOINTMENT_MANAGE),
            Map.entry("ViewingAppointmentController.rejectReschedule", Permissions.APPOINTMENT_MANAGE),
            Map.entry("ViewingAppointmentController.cancelByOwner", Permissions.APPOINTMENT_MANAGE),
            Map.entry("ViewingAppointmentController.completeAppointment", Permissions.APPOINTMENT_MANAGE),
            Map.entry("RentalRequestController.createRentalRequest", Permissions.RENTAL_REQUEST_CREATE),
            Map.entry("RentalRequestController.acceptRentalRequest", Permissions.RENTAL_REQUEST_MANAGE),
            Map.entry("RentalRequestController.rejectRentalRequest", Permissions.RENTAL_REQUEST_MANAGE),
            Map.entry("FavoriteController.toggleFavorite", Permissions.FAVORITE_USE),
            Map.entry("ContractController.createDraft", Permissions.CONTRACT_MANAGE),
            Map.entry("ContractController.updateRevision", Permissions.CONTRACT_MANAGE),
            Map.entry("ContractController.triggerPreview", Permissions.CONTRACT_MANAGE),
            Map.entry("ContractController.sendToTenant", Permissions.CONTRACT_MANAGE),
            Map.entry("ContractController.sign", Permissions.CONTRACT_SIGN),
            Map.entry("ContractSignatureController.initiateSignature", Permissions.CONTRACT_SIGN),
            Map.entry("ContractSignatureController.retrySignature", Permissions.CONTRACT_SIGN),
            Map.entry("ComplaintController.open", Permissions.COMPLAINT_CREATE),
            Map.entry("MembershipController.checkout", Permissions.MEMBERSHIP_PURCHASE),
            Map.entry("MembershipController.addonCheckout", Permissions.MEMBERSHIP_PURCHASE),
            Map.entry("MembershipController.ethQuote", Permissions.MEMBERSHIP_PURCHASE),
            Map.entry("VnptKycController.start", Permissions.KYC_SUBMIT),
            Map.entry("VnptKycController.upload", Permissions.KYC_SUBMIT),
            Map.entry("VnptKycController.step", Permissions.KYC_SUBMIT),
            Map.entry("VnptKycController.finish", Permissions.KYC_SUBMIT));

    @AfterEach void clear() {
        SecurityContextHolder.clearContext();
        UserContextHolder.clear();
    }

    @Test void mappedHandlersCarryExpectedPermissionAndEveryUserScopeCodeIsUsed() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        Map<String, String> actual = new TreeMap<>();
        for (String pkg : List.of("listing", "contract", "payment", "user")) {
            for (BeanDefinition def : scanner.findCandidateComponents("com.hs.api.controller." + pkg)) {
                Class<?> type = Class.forName(def.getBeanClassName());
                for (Method m : type.getDeclaredMethods()) {
                    PreAuthorize pre = m.getAnnotation(PreAuthorize.class);
                    if (pre == null) continue;
                    if (!Modifier.isPublic(m.getModifiers())
                            || AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class) == null) continue;
                    actual.put(type.getSimpleName() + "." + m.getName(), pre.value());
                }
            }
        }
        Map<String, String> expected = new TreeMap<>();
        EXPECTED.forEach((h, code) -> expected.put(h, "hasAuthority('" + code + "')"));
        // Exact match: adding or dropping a gate on a handler must be a deliberate test change.
        assertEquals(expected, actual);
        assertEquals(Permissions.USER_SCOPE, new HashSet<>(EXPECTED.values()));
    }

    @Configuration @EnableMethodSecurity(proxyTargetClass = true)
    static class Config {
        @Bean ListingFavoriteService favorites() { return mock(ListingFavoriteService.class); }
        @Bean FavoriteController favoriteController(ListingFavoriteService s) { return new FavoriteController(s); }
    }

    private static void login(String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "u-1", "n/a", Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()));
        UserContextHolder.set(new UserContext("u-1", null));
    }

    @Test void deniedPermissionIs403ApiResponseAndGrantedPermissionPasses() throws Exception {
        try (var ctx = new AnnotationConfigApplicationContext(Config.class)) {
            var mvc = standaloneSetup(ctx.getBean(FavoriteController.class))
                    .setControllerAdvice(new GlobalException()).build();
            login("USER");
            mvc.perform(post("/favorites/l-1/toggle")).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(1009));
            login("USER", Permissions.FAVORITE_USE);
            mvc.perform(post("/favorites/l-1/toggle")).andExpect(status().isOk());
        }
    }
}
