package com.paymesh.controller;

import jakarta.validation.Valid;
import com.paymesh.dto.UserDtos.*;
import com.paymesh.service.UserOperationsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class UserController {

    @Autowired
    private UserOperationsService userOperationsService;

    @PostMapping("/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@RequestBody @Valid RegisterRequest request) {
        return userOperationsService.register(request);
    }

    @PostMapping("/auth/login")
    public AuthResponse login(@RequestBody @Valid LoginRequest request) {
        return userOperationsService.login(request);
    }

    @GetMapping("/users/me")
    public UserResponse me(@AuthenticationPrincipal Jwt jwt) {
        return UserResponse.of(userOperationsService.getUser(Long.valueOf(jwt.getSubject())));
    }

    @PutMapping("/users/me")
    public UserResponse update(@AuthenticationPrincipal Jwt jwt, @RequestBody @Valid UpdateUserRequest request) {
        return UserResponse.of(userOperationsService.updateUser(Long.valueOf(jwt.getSubject()), request));
    }

    @DeleteMapping("/users/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt) {
        userOperationsService.deleteUser(Long.valueOf(jwt.getSubject()));
    }

    @GetMapping("/users/lookup")
    public UserLookupResponse lookup(@RequestParam String mobile) {
        return userOperationsService.lookupByMobile(mobile);
    }
}
