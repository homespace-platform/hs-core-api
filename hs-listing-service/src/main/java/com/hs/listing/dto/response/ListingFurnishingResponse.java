package com.hs.listing.dto.response; import com.hs.listing.model.constant.HandoverCondition;
/** Một dòng của bảng {{#equipmentTable}} trong hợp đồng: STT | Tên tài sản | Số lượng | Hiện trạng bàn giao. */
public record ListingFurnishingResponse(int index,String itemCode,String assetName,Integer quantity,HandoverCondition handoverCondition,String handoverConditionLabel,String conditionNote,String conditionText) {}
