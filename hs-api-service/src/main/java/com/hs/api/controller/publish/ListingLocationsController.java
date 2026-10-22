package com.hs.api.controller.publish;
import com.hs.common.dto.ApiResponse;
import jakarta.persistence.EntityManager;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** Filter facets from real published listings, not a second administrative catalog/service. */
@RestController @RequestMapping("/public/locations") @RequiredArgsConstructor @Transactional(readOnly=true)
public class ListingLocationsController {
    public record Region(String code,String name) {}
    private final EntityManager em;
    @GetMapping("/cities") public ApiResponse<List<Region>> cities() {
        return ApiResponse.<List<Region>>builder().result(em.createQuery(
            "select distinct a.cityId,a.city from ListingAddress a, Listing l where a.listingId=l.id and a.active=true and l.active=true and l.status=com.hs.listing.model.constant.ListingStatus.PUBLISHED order by a.city",Object[].class)
            .getResultList().stream().map(r -> new Region((String)r[0],(String)r[1])).toList()).build();
    }
    @GetMapping("/cities/{cityId}/wards") public ApiResponse<List<Region>> wards(@PathVariable String cityId) {
        return ApiResponse.<List<Region>>builder().result(em.createQuery(
            "select distinct a.wardId,a.ward from ListingAddress a, Listing l where a.listingId=l.id and a.cityId=:city and a.active=true and l.active=true and l.status=com.hs.listing.model.constant.ListingStatus.PUBLISHED order by a.ward",Object[].class)
            .setParameter("city",cityId).getResultList().stream().map(r -> new Region((String)r[0],(String)r[1])).toList()).build();
    }
}
