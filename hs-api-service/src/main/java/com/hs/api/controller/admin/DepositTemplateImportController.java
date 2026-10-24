package com.hs.api.controller.admin;

import com.hs.common.context.UserContextHolder;
import com.hs.common.dto.ApiResponse;
import com.hs.contract.dto.request.CreateContractTemplateRequest;
import com.hs.contract.dto.response.ContractTemplateResponse;
import com.hs.contract.repository.ContractTemplateRepository;
import com.hs.contract.service.ContractTemplateService;
import com.hs.listing.model.constant.ListingCategory;
import com.hs.storage.model.constant.*;
import com.hs.storage.service.StorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** Explicit admin import; never silently replaces or publishes existing templates on startup. */
@RestController @RequiredArgsConstructor @PreAuthorize("hasAuthority('ADMIN')")
@RequestMapping({"/admin/contract-templates","/api/v1/admin/contract-templates"})
public class DepositTemplateImportController {
    private final ContractTemplateRepository templates;
    private final ContractTemplateService service;
    private final StorageService storage;
    @PostMapping("/import-deposit-defaults")
    public ApiResponse<List<ContractTemplateResponse>> importDefaults() throws java.io.IOException {
        var result=new ArrayList<ContractTemplateResponse>();
        String[] names={"HomeSpace_01_Hop_Dong_Dat_Coc_Nha_Nguyen_Can.docx","HomeSpace_02_Hop_Dong_Dat_Coc_Can_Ho_Chung_Cu.docx","HomeSpace_03_Hop_Dong_Dat_Coc_Phong_Tro.docx"};
        var categories=List.of(ListingCategory.HOUSE,ListingCategory.APARTMENT,ListingCategory.ROOM);
        for(int i=0;i<names.length;i++) {
            var existing=templates.findSystemTemplatesByCategoryAndKind(categories.get(i),"DEPOSIT");
            if(!existing.isEmpty()){result.add(service.getTemplate(existing.getFirst().getId()));continue;}
            byte[] bytes;
            try (var stream = new ClassPathResource("deposit-templates/"+names[i]).getInputStream()) {
                bytes = stream.readAllBytes();
            }
            var stored=storage.uploadDirect(bytes,names[i],"application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                StoragePurpose.CONTRACT_DOCUMENT,"CONTRACT_TEMPLATE",null,StorageVisibility.PRIVATE,UserContextHolder.get().userId());
            result.add(service.createTemplate(CreateContractTemplateRequest.builder().name("Hợp đồng cọc - "+categories.get(i))
                .description("Mẫu D2 từ bộ Word HomeSpace; xem thử và phát hành trước sử dụng.").category(categories.get(i)).documentKind("DEPOSIT")
                .storageObjectId(stored.id()).originalFileName(names[i]).build()));
        }
        return ApiResponse.<List<ContractTemplateResponse>>builder().result(result).message("Đã nhập mẫu cọc ở trạng thái nháp. Xem thử và phát hành từng mẫu.").build();
    }
}
