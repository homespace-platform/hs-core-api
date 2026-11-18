package com.hs.api.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.hs.api.advice.handler.GlobalException;
import com.hs.common.context.UserContext;
import com.hs.common.context.UserContextHolder;
import com.hs.common.security.Permissions;
import com.hs.payment.service.BankAccountService;
import com.hs.user.dto.response.AddressResponse;
import com.hs.user.dto.response.UserProfileResponse;
import com.hs.user.service.UserService;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

class ProfileGateTest {

    /** User-scope handlers deliberately left open: favorites, KYC itself (must stay reachable) and owner cancel. */
    private static final Set<String> NOT_GATED = Set.of("FavoriteController.toggleFavorite",
            "VnptKycController.start", "VnptKycController.upload", "VnptKycController.step",
            "VnptKycController.finish", "ViewingAppointmentController.cancelByOwner");

    private final UserService users = mock(UserService.class);
    private final BankAccountService banks = mock(BankAccountService.class);
    private final ProfileRequirementService service = new ProfileRequirementService(users, banks);

    @AfterEach void clear() {
        SecurityContextHolder.clearContext();
        UserContextHolder.clear();
    }

    private static AddressResponse address() {
        return AddressResponse.builder().refId("r").street("S").cityId("1").wardId("2")
                .latitude(BigDecimal.ONE).longitude(BigDecimal.TEN).build();
    }

    private static UserProfileResponse.UserProfileResponseBuilder full() {
        return UserProfileResponse.builder().firstName("A").lastName("B").phone("0900000000")
                .address(address()).kycVerified(true);
    }

    private List<String> missing(UserProfileResponse profile, boolean bank) {
        UserContextHolder.set(new UserContext("u-1", null));
        when(users.getUserProfile()).thenReturn(profile);
        when(banks.hasActiveBankAccount("u-1")).thenReturn(bank);
        return service.evaluate().missing();
    }

    @Test void eachRequirementIsReportedIndependentlyInFixedOrder() {
        assertEquals(List.of(), missing(full().build(), true));
        assertEquals(List.of("PROFILE"), missing(full().phone(" ").build(), true));
        assertEquals(List.of("PROFILE"), missing(full().lastName(null).build(), true));
        assertEquals(List.of("ADDRESS"), missing(full().address(null).build(), true));
        assertEquals(List.of("BANK_ACCOUNT"), missing(full().build(), false));
        assertEquals(List.of("KYC"), missing(full().kycVerified(false).build(), true));
        assertEquals(List.of("KYC"), missing(full().kycVerified(null).build(), true));
        assertEquals(List.of("PROFILE", "ADDRESS", "BANK_ACCOUNT", "KYC"),
                missing(UserProfileResponse.builder().build(), false));
    }

    @RestController @RequestMapping("/t")
    static class Handlers {
        @RequiresCompleteProfile @PreAuthorize("hasAuthority('LISTING_POST')") @PostMapping("/gated")
        public String gated() { return "ok"; }

        @PostMapping("/open")
        public String open() { return "ok"; }
    }

    private MockMvc mvc() {
        return standaloneSetup(new Handlers()).setControllerAdvice(new GlobalException())
                .addInterceptors(new ProfileGateInterceptor(service)).build();
    }

    private static void login(String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "u-1", "n/a", Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()));
        UserContextHolder.set(new UserContext("u-1", null));
    }

    @Test void blocksWith1010AndMissingListOnlyForGatedHandler() throws Exception {
        login("USER", Permissions.LISTING_POST);
        when(users.getUserProfile()).thenReturn(full().address(null).kycVerified(false).build());
        mvc().perform(post("/t/gated")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(1010))
                .andExpect(jsonPath("$.message").value(
                        "Vui lòng cập nhật đầy đủ hồ sơ: Địa chỉ, Tài khoản ngân hàng, Xác minh danh tính (KYC)"))
                .andExpect(jsonPath("$.result.missing[0]").value("ADDRESS"))
                .andExpect(jsonPath("$.result.missing[1]").value("BANK_ACCOUNT"))
                .andExpect(jsonPath("$.result.missing[2]").value("KYC"))
                .andExpect(jsonPath("$.result.missing.length()").value(3));
        mvc().perform(post("/t/open")).andExpect(status().isOk());
    }

    @Test void permitsWhenProfileCompleteAndSkipsCheckWhenPermissionOrIdentityMissing() throws Exception {
        login("USER", Permissions.LISTING_POST);
        when(users.getUserProfile()).thenReturn(full().build());
        when(banks.hasActiveBankAccount("u-1")).thenReturn(true);
        mvc().perform(post("/t/gated")).andExpect(status().isOk());

        // Missing permission: interceptor defers so method security answers 1009 first.
        UserProfileResponse incomplete = UserProfileResponse.builder().build();
        when(users.getUserProfile()).thenReturn(incomplete);
        login("USER");
        mvc().perform(post("/t/gated")).andExpect(status().isOk());

        // Anonymous: no profile lookup (method security answers 401).
        SecurityContextHolder.clearContext();
        UserContextHolder.clear();
        mvc().perform(post("/t/gated")).andExpect(status().isOk());
    }

    @Test void gateIsOnEveryUserScopeHandlerExceptFavoriteKycAndOwnerCancel() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        Set<String> gated = new TreeSet<>();
        Set<String> expected = new TreeSet<>();
        for (String pkg : List.of("listing", "contract", "payment", "user", "admin", "storage")) {
            for (BeanDefinition def : scanner.findCandidateComponents("com.hs.api.controller." + pkg)) {
                Class<?> type = Class.forName(def.getBeanClassName());
                for (Method m : type.getDeclaredMethods()) {
                    if (!Modifier.isPublic(m.getModifiers())
                            || AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class) == null) continue;
                    String id = type.getSimpleName() + "." + m.getName();
                    if (m.isAnnotationPresent(RequiresCompleteProfile.class)) gated.add(id);
                    PreAuthorize pre = m.getAnnotation(PreAuthorize.class);
                    boolean userScope = pre != null && Permissions.USER_SCOPE.stream()
                            .anyMatch(code -> pre.value().equals("hasAuthority('" + code + "')"));
                    if (userScope && !NOT_GATED.contains(id)) expected.add(id);
                }
            }
        }
        assertTrue(expected.size() >= 25, "scan found too few user-scope handlers: " + expected);
        assertEquals(expected, gated);
    }

    @Test void interceptorDoesNotTouchServicesForUngatedHandler() throws Exception {
        login("USER");
        mvc().perform(post("/t/open")).andExpect(status().isOk());
        verify(users, never()).getUserProfile();
        verify(banks, never()).hasActiveBankAccount(anyString());
    }
}
