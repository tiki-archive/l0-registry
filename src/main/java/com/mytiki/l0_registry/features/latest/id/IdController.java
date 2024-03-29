/*
 * Copyright (c) TIKI Inc.
 * MIT license. See LICENSE file in root directory.
 */

package com.mytiki.l0_registry.features.latest.id;

import com.mytiki.l0_registry.utilities.AddressSignature;
import com.mytiki.l0_registry.utilities.Constants;
import com.mytiki.spring_rest_api.ApiConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.annotation.Secured;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

@Tag(name = "")
@RestController
@RequestMapping(value = IdController.PATH_CONTROLLER)
public class IdController {

    public static final String PATH_CONTROLLER = ApiConstants.API_LATEST_ROUTE + "id";

    private final IdService service;

    public IdController(IdService service) {
        this.service = service;
    }

    @Operation(operationId = Constants.PROJECT_DASH_PATH +  "-id-post",
            summary = "Register Address", description = "Register an address for a custom ID",
            security = @SecurityRequirement(name = "oauth", scopes = "registry"))
    @RequestMapping(method = RequestMethod.POST)
    public IdAORsp postId(Principal principal,
                          @RequestHeader(AddressSignature.HEADER) String addressSignature,
                          @RequestHeader(value = "X-Customer-Authorization", required = false) String customerToken,
                          @RequestBody IdAOReq body) {
        if(customerToken == null) customerToken = "";
        return service.register(
                principal.getName(),
                body,
                new AddressSignature(addressSignature),
                customerToken.replace("Bearer: ", ""));
    }

    @Operation(operationId = Constants.PROJECT_DASH_PATH +  "-id-get",
            summary = "Get Addresses", description = "Get addresses registered for a custom ID",
            security = @SecurityRequirement(name = "oauth", scopes = "registry"))
    @RequestMapping(method = RequestMethod.GET, path = "/{id}/addresses")
    public IdAORsp getAddresses(
            Principal principal,
            @RequestHeader(AddressSignature.HEADER) String addressSignature,
            @PathVariable("id") String id) {
        return service.get(
                principal.getName(),
                id,
                new AddressSignature(addressSignature));
    }

    @Operation(operationId = Constants.PROJECT_DASH_PATH +  "-id-delete",
            summary = "Delete ID", description = "Delete a custom ID",
            security = @SecurityRequirement(name = "oauth", scopes = "registry:admin"))
    @Secured("SCOPE_admin")
    @RequestMapping(method = RequestMethod.DELETE, path = "/{id}")
    public void deleteId(Principal principal, @PathVariable("id") String id) {
        service.delete(principal.getName(), id);
    }

    @Operation(operationId = Constants.PROJECT_DASH_PATH +  "-id-get-pubkey",
            summary = "Get Public Key", description = "Returns the public key for a custom ID",
            security = @SecurityRequirement(name = "oauth", scopes = "registry"))
    @RequestMapping(method = RequestMethod.GET, path = "/{id}/pubkey")
    public IdAORspKey getPubKey(Principal principal, @PathVariable("id") String id) {
        return service.pubKey(principal.getName(), id);
    }
}
