/*
 * Copyright (c) TIKI Inc.
 * MIT license. See LICENSE file in root directory.
 */

package com.mytiki.l0_registry.features.latest.id;

import com.mytiki.l0_registry.features.latest.address.AddressService;
import com.mytiki.l0_registry.features.latest.config.ConfigDO;
import com.mytiki.l0_registry.features.latest.config.ConfigService;
import com.mytiki.l0_registry.features.latest.sign.SignService;
import com.mytiki.l0_registry.utilities.AddressSignature;
import com.mytiki.l0_registry.utilities.RSAFacade;
import com.mytiki.l0_registry.utilities.SHA3Facade;
import com.mytiki.spring_rest_api.ApiExceptionBuilder;
import jakarta.transaction.Transactional;
import org.apache.commons.codec.binary.Base64;
import org.bouncycastle.asn1.pkcs.RSAPublicKey;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class IdService {
    private final IdRepository repository;
    private final ConfigService configService;
    private final SignService signService;
    private final AddressService addressService;

    public IdService(
            IdRepository repository,
            ConfigService configService,
            SignService signService,
            AddressService addressService) {
        this.repository = repository;
        this.configService = configService;
        this.signService = signService;
        this.addressService = addressService;
    }

    public IdAORsp get(String appId, String id, AddressSignature addressSignature){
        try {
            guardForSignature(addressSignature);
            String address = Base64.encodeBase64String(
                    SHA3Facade.sha256(Base64.decodeBase64(addressSignature.getPubKey())));
            Optional<IdDO> found = repository.getByCustomerIdAndConfigAppId(id, appId);
            if (found.isPresent()) {
                Set<String> addressList = found.get()
                        .getAddresses()
                        .stream()
                        .map(a -> Base64.encodeBase64String(a.getAddress()))
                        .collect(Collectors.toSet());
                if (!addressList.contains(address))
                    throw new ApiExceptionBuilder(HttpStatus.UNAUTHORIZED)
                            .message("Address validation failed")
                            .detail("Address is not a member")
                            .help("Try adding the address to the id first")
                            .build();
                IdAORsp rsp = new IdAORsp();
                rsp.setAddresses(addressList);
                rsp.setSignKey(signService.get(found.get()));
                return rsp;
            }
            return null;
        }catch (NoSuchAlgorithmException e){
            throw new ApiExceptionBuilder(HttpStatus.UNPROCESSABLE_ENTITY)
                    .message("Address validation failed")
                    .detail(e.getMessage())
                    .build();
        }
    }

    @Transactional
    public IdAORsp register(String appId, IdAOReq req, AddressSignature addressSignature){
        IdAORsp rsp = new IdAORsp();
        guardForSignature(addressSignature);
        guardForAddress(req.getAddress(), addressSignature.getPubKey());
        Optional<IdDO> found = repository.getByCustomerIdAndConfigAppId(req.getId(), appId);
        if(found.isEmpty()){
            ConfigDO config = configService.getCreate(appId);
            IdDO save = new IdDO();
            save.setConfig(config);
            save.setCustomerId(req.getId());
            save.setCreated(ZonedDateTime.now());
            save = repository.save(save);
            rsp.setSignKey(signService.cycle(save));
            addressService.save(save, req.getAddress());
            rsp.setAddresses(Set.of(req.getAddress()));
        }else{
            addressService.save(found.get(), req.getAddress());
            rsp.setSignKey(signService.get(found.get()));
            Set<String> addresses = found.get()
                    .getAddresses()
                    .stream()
                    .map(a -> Base64.encodeBase64String(a.getAddress()))
                    .collect(Collectors.toSet());
            addresses.add(req.getAddress());
            rsp.setAddresses(addresses);
        }
        return rsp;
    }

    private void guardForSignature(AddressSignature signature){
        try{
            RSAPublicKey publicKey = RSAFacade.decodePublicKey(signature.getPubKey());
            boolean isValid = RSAFacade.verify(publicKey, signature.getStringToSign(), signature.getSignature());
            if(!isValid)
                throw new ApiExceptionBuilder(HttpStatus.BAD_REQUEST)
                        .message("Failed to validate key/signature paid")
                        .detail("Signature does not match plaintext")
                        .properties(
                                "stringToSign", signature.getStringToSign(),
                                "signature", signature.getSignature())
                        .build();
        } catch (IOException | IllegalArgumentException e) {
            throw new ApiExceptionBuilder(HttpStatus.BAD_REQUEST)
                    .message("Failed to validate key/signature paid")
                    .detail("Encoding is incorrect")
                    .cause(e.getCause())
                    .build();
        }
    }

    private void guardForAddress(String address, String pubKey) {
        try {
            byte[] addressBytes = Base64.decodeBase64(address);
            byte[] hashedKey = SHA3Facade.sha256(Base64.decodeBase64(pubKey));
            if(!Arrays.equals(addressBytes, hashedKey)){
                throw new ApiExceptionBuilder(HttpStatus.UNAUTHORIZED)
                        .message("Address validation failed")
                        .detail("Public key does not match the address provided")
                        .build();
            }
        }catch (NoSuchAlgorithmException e){
            throw new ApiExceptionBuilder(HttpStatus.UNPROCESSABLE_ENTITY)
                    .message("Address validation failed")
                    .detail(e.getMessage())
                    .build();
        }
    }
}
