package com.paymesh.service;

import lombok.extern.slf4j.Slf4j;
import com.paymesh.constants.TopicConstants;
import com.paymesh.dto.UserDtos.*;
import com.paymesh.dto.UserWalletCreationRequest;
import com.paymesh.model.User;
import com.paymesh.outbox.Outbox;
import com.paymesh.repository.UserRepository;
import com.paymesh.auth.MfaRequiredException;
import com.paymesh.auth.TokenIssuer;
import com.paymesh.auth.TwoFactor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

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
    private TokenIssuer tokenIssuer;

    @Autowired
    private TwoFactor twoFactor;

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
        List<String> amr = List.of("pwd");
        if (user.isTotpEnabled()) {
            if (request.otp() == null || request.otp().isBlank()) {
                throw new MfaRequiredException(); // not a failed attempt: the password was right
            }
            if (!twoFactor.verifyForUser(user.getUserId(), request.otp())) {
                loginAttemptService.recordFailure(user.getUserId()); // wrong codes count toward the lockout
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid code");
            }
            amr = List.of("pwd", "otp");
        }
        loginAttemptService.recordSuccess(user);
        return new AuthResponse(tokenIssuer.issueAccess(user, amr), UserResponse.of(user));
    }

    /** A fresh access token for an existing session (refresh). */
    public AuthResponse sessionFor(long userId) {
        User user = getUser(userId);
        return new AuthResponse(issueToken(user), UserResponse.of(user));
    }

    /** Proves the second factor again (large payments ask for it): a token with amr otp and a fresh iat. */
    public AuthResponse stepUp(long userId, String code) {
        User user = getUser(userId);
        loginAttemptService.ensureNotLocked(user); // fast path; verifyForUser re-checks under the row lock
        if (!twoFactor.verifyForUser(userId, code)) {
            loginAttemptService.recordFailure(userId);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That code didn't match");
        }
        return new AuthResponse(tokenIssuer.issueAccess(user, List.of("pwd", "otp")), UserResponse.of(user));
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
        return tokenIssuer.issueAccess(user, List.of("pwd"));
    }

    private static ResponseStatusException invalidCredentials() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
