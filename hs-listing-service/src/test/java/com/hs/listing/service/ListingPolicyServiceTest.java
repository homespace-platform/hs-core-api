package com.hs.listing.service;

import com.hs.common.advice.entity.AppException;
import com.hs.listing.config.ListingPolicyCatalog;
import com.hs.listing.dto.request.ListingPolicyRequest;
import com.hs.listing.model.*;
import com.hs.listing.model.constant.ListingCategory;
import com.hs.listing.repository.ListingPolicyDefinitionRepository;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ListingPolicyServiceTest {
    ListingPolicyDefinitionRepository repository = mock(ListingPolicyDefinitionRepository.class);
    ListingPolicyService service() { when(repository.findAllByActiveTrueOrderBySortOrderAsc()).thenReturn(ListingPolicyCatalog.defaults()); return new ListingPolicyService(repository); }
    List<ListingPolicyRequest> values(ListingPolicyService service,ListingCategory category) {
        return service.options(category).stream().map(d -> new ListingPolicyRequest(d.code(),switch(d.valueType()) {
            case "PERMISSION" -> "APPROVAL_REQUIRED"; case "INTEGER" -> d.minimumValue().toString(); default -> "Giữ vệ sinh và tuân thủ nội quy tài sản thuê";
        },"Điều kiện do chủ nhà nhập")).toList();
    }
    @Test void categoryCatalogIsDynamicAndNoPetsAmenityOrLateFeeFieldsAreMixedIn() {
        var service = service();
        assertEquals(8,service.options(ListingCategory.HOUSE).size());
        assertEquals(9,service.options(ListingCategory.APARTMENT).size()); assertEquals(9,service.options(ListingCategory.ROOM).size());
        assertTrue(service.options(ListingCategory.HOUSE).stream().anyMatch(d -> d.code().equals("alterationsPolicy")));
        assertFalse(service.options(ListingCategory.ROOM).stream().anyMatch(d -> d.code().equals("buildingRulesPolicy")));
        assertFalse(service.options(ListingCategory.HOUSE).stream().anyMatch(d -> d.code().startsWith("latePayment")));
        var extra = new ListingPolicyDefinition(); extra.setCode("gardenUsePolicy"); extra.setLabel("Sử dụng vườn"); extra.setValueType("TEXT"); extra.setCategories(Set.of(ListingCategory.HOUSE));
        var defs = new ArrayList<>(ListingPolicyCatalog.defaults()); defs.add(extra); when(repository.findAllByActiveTrueOrderBySortOrderAsc()).thenReturn(defs);
        assertTrue(service.options(ListingCategory.HOUSE).stream().anyMatch(d -> d.code().equals("gardenUsePolicy")));
    }
    @Test void draftMayBePartialButPublishedListingRequiresEveryCategoryPolicy() {
        var service = service(); var listing = Listing.builder().category(ListingCategory.ROOM).build();
        service.attach(listing,List.of(),false); assertTrue(listing.getPolicies().isEmpty());
        assertThrows(AppException.class,() -> service.attach(listing,List.of(),true));
        service.attach(listing,values(service,ListingCategory.ROOM),true);
        assertEquals(9,listing.getPolicies().size());
        var row = ListingPolicyService.snapshot(listing).getFirst();
        assertEquals("APPROVAL_REQUIRED",row.value()); assertTrue(row.displayValue().startsWith("Cần chủ nhà chấp thuận trước"));
        assertTrue(row.displayValue().contains("Điều kiện do chủ nhà nhập"));
    }
    @Test void forgedCodesCategoriesDuplicatesPermissionsAndFractionalDaysAreRejected() {
        var service = service(); var listing = Listing.builder().category(ListingCategory.ROOM).build();
        for (var row : List.of(new ListingPolicyRequest("latePaymentFeeAmount","100000",""),
                new ListingPolicyRequest("alterationsPolicy","ALLOWED",""),new ListingPolicyRequest("petPolicy","true",""),
                new ListingPolicyRequest("depositRefundDays","1.5",""),new ListingPolicyRequest("depositRefundDays","-1",""),new ListingPolicyRequest("depositRefundDays","61","")))
            assertThrows(AppException.class,() -> service.attach(listing,List.of(row),false));
        var row = new ListingPolicyRequest("petPolicy","ALLOWED","");
        assertThrows(AppException.class,() -> service.attach(listing,List.of(row,row),false));
    }
}
