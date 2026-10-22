package com.hs.user.service.impl;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hs.common.advice.entity.AppException;
import com.hs.user.advice.entity.enums.UserErrorCode;
import com.hs.user.dto.request.UpsertAddressRequest;
import com.hs.user.dto.response.AddressResponse;
import com.hs.user.mapper.AddressMapper;
import com.hs.user.model.Address;
import com.hs.user.model.User;
import com.hs.user.repository.AddressRepository;
import com.hs.user.service.AddressService;
import com.hs.user.utils.CurrentUserUtils;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@Slf4j
@Transactional
public class AddressServiceImpl implements AddressService {

    AddressRepository addressRepository;
    CurrentUserUtils currentUserUtils;
    com.hs.common.location.VietmapService vietmapService;

    @Override
    @Transactional(readOnly = true)
    public AddressResponse getCurrentUserAddress() {
        return AddressMapper.mapToAddressResponse(requireActiveAddress());
    }

    @Override
    public AddressResponse upsertCurrentUserAddress(UpsertAddressRequest request) {
        User user = currentUserUtils.getCurrentUser();
        Address address = addressRepository.findByUser_Id(user.getId()).orElseGet(Address::new);
        address.setUser(user);
        address.setActive(true);
        vietmapService.apply(address, request.refId(), request.addressNote());
        Address saved = addressRepository.save(address);
        log.info("Upserted address {} for user {}", saved.getId(), user.getId());
        return AddressMapper.mapToAddressResponse(saved);
    }

    @Override
    public void deleteCurrentUserAddress() {
        Address address = requireActiveAddress();
        address.setActive(false);
        addressRepository.save(address);
        log.info("Soft-deleted address {} for user {}", address.getId(), address.getUser().getId());
    }

    private Address requireActiveAddress() {
        return addressRepository
                .findByUser_IdAndActiveTrue(currentUserUtils.getCurrentUserId())
                .orElseThrow(() -> new AppException(UserErrorCode.ADDRESS_NOT_EXISTED));
    }

}
