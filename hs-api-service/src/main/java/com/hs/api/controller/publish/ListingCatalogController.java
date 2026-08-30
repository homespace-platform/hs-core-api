package com.hs.api.controller.publish;

import com.hs.common.dto.ApiResponse;
import com.hs.listing.dto.response.ListingOptionsResponse;
import com.hs.listing.model.constant.ListingCategory;
import com.hs.listing.service.ListingOptionsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/public/listing-catalog")
@RequiredArgsConstructor
public class ListingCatalogController {
    private final ListingOptionsService listingOptionsService;

    @GetMapping
    public ApiResponse<ListingOptionsResponse> getCatalog(@RequestParam ListingCategory category) {
        return ApiResponse.<ListingOptionsResponse>builder()
                .result(listingOptionsService.getOptions(category))
                .build();
    }
}
