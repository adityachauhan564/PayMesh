package com.paymesh.service;

import lombok.extern.slf4j.Slf4j;
import com.paymesh.constants.TopicConstants;
import com.paymesh.dto.UserDtos.*;
import com.paymesh.dto.UserWalletCreationRequest;
import com.paymesh.model.User;
import com.paymesh.outbox.Outbox;
import com.paymesh.repository.UserRepository;
import com.paymesh.security.JwtProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;

@Service
@Slf4j
public class UserOperationsService {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private Outbox outbox;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private JwtProperties jwtProperties;

    @Autowired
    private LoginAttemptService loginAttemptService;

    /** The user and its wallet-creation event are committed together: no user without a wallet on the way. */
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByUserMobileNo(request.mobileNo())) {
            throw conflict("Mobile number is already registered");
        }
        if (userRepository.existsByUserEmailId(request.emailId())) {
            throw conflict("Email is already registered");
        }
        if (request.pan() != null && userRepository.existsByUserPan(request.pan())) {
            throw conflict("PAN is already registered");
        }
        User user = new User();
        user.setUserFullName(request.fullName());
        user.setUserMobileNo(request.mobileNo());
        user.setUserEmailId(request.emailId());
        user.setUserAddress(request.address());
        user.setUserPan(request.pan());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        User savedUser = userRepository.save(user);
        log.info("User account is created for UserId: {}", savedUser.getUserId());

        publish(TopicConstants.USER_CREATION_TOPIC, savedUser);
        return new AuthResponse(issueToken(savedUser), UserResponse.of(savedUser));
    }

    public AuthResponse login(LoginRequest request) {
        User user = (request.username().contains("@")
                ? userRepository.findByUserEmailId(request.username())
                : userRepository.findByUserMobileNo(request.username()))
                .orElseThrow(UserOperationsService::invalidCredentials);
        loginAttemptService.ensureNotLocked(user);
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            loginAttemptService.recordFailure(user.getUserId());
            throw invalidCredentials();
        }
        loginAttemptService.recordSuccess(user);
        return new AuthResponse(issueToken(user), UserResponse.of(user));
    }

    public User getUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }

    @Transactional
    public User updateUser(Long userId, UpdateUserRequest request) {
        User user = getUser(userId);
        if (!user.getUserEmailId().equals(request.emailId()) && userRepository.existsByUserEmailId(request.emailId())) {
            throw conflict("Email is already registered");
        }
        user.setUserFullName(request.fullName());
        user.setUserEmailId(request.emailId());
        user.setUserAddress(request.address());
        User saved = userRepository.save(user);
        publish(TopicConstants.USER_UPDATION_TOPIC, saved);
        return saved;
    }

    @Transactional
    public void deleteUser(Long userId) {
        userRepository.delete(getUser(userId));
        outbox.publish(TopicConstants.USER_DELETION_TOPIC, userId.toString(), userId);
    }

    public UserLookupResponse lookupByMobile(String mobileNo) {
        return userRepository.findByUserMobileNo(mobileNo)
                .map(u -> new UserLookupResponse(u.getUserId(), u.getUserFullName()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No wallet user with this mobile number"));
    }

    private void publish(String topic, User user) {
        outbox.publish(topic, user.getUserId().toString(),
                new UserWalletCreationRequest(user.getUserId(), user.getUserFullName(), user.getUserEmailId()));
    }

    private String issueToken(User user) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.issuer())
                .subject(user.getUserId().toString())
                .claim("name", user.getUserFullName())
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofHours(24)))
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    private static ResponseStatusException invalidCredentials() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
