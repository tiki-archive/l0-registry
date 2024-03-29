/*
 * Copyright (c) TIKI Inc.
 * MIT license. See LICENSE file in root directory.
 */

package com.mytiki.l0_registry.features.latest.sign;

import com.mytiki.l0_registry.features.latest.id.IdDO;
import com.mytiki.l0_registry.utilities.RSAFacade;
import com.mytiki.spring_rest_api.ApiExceptionBuilder;
import com.nimbusds.jose.JOSEException;
import org.bouncycastle.asn1.pkcs.RSAPrivateKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.time.ZonedDateTime;
import java.util.Base64;
import java.util.Optional;

public class SignService {
    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private final SignRepository repository;

    public SignService(SignRepository repository) {
        this.repository = repository;
    }

    public String cycle(IdDO id){
        try {
            SignDO save = new SignDO();
            save.setId(id);
            save.setCreated(ZonedDateTime.now());
            save.setPrivateKey(RSAFacade.generate());
            repository.save(save);
            return Base64.getEncoder().encodeToString(save.getPrivateKey());
        } catch (JOSEException e) {
            throw new ApiExceptionBuilder(HttpStatus.UNPROCESSABLE_ENTITY)
                    .message("Key generation failed")
                    .detail(e.getMessage())
                    .build();
        }
    }

    public String get(IdDO id){
        Optional<SignDO> latest = repository.getFirstByIdOrderByCreatedDesc(id);
        if(latest.isEmpty())
            return cycle(id);
        else return Base64.getEncoder().encodeToString(latest.get().getPrivateKey());
    }

    public String getPublicKey(IdDO id){
        Optional<SignDO> latest = repository.getFirstByIdOrderByCreatedDesc(id);
        if(latest.isPresent()){
            try {
                 RSAPrivateKey privateKey = RSAFacade.decodePrivateKey(latest.get().getPrivateKey());
                 return Base64.getEncoder().encodeToString(RSAFacade.toPublic(privateKey).getEncoded());
            } catch (IOException e) {
                logger.error("Failed to decode private key", e);
            }
        }
        return null;
    }

    public void deleteAllById(IdDO id){
        repository.deleteAllById(id);
    }
}
