package com.paymesh.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import com.paymesh.model.User;

import java.time.LocalDateTime;

public final class UserDtos {

    private UserDtos() {
    }

    public record RegisterRequest(
            @NotBlank @Size(max = 100) String fullName,
            @NotBlank @Pattern(regexp = "[6-9]\\d{9}", message = "must be a valid 10 digit mobile number") String mobileNo,
            @NotBlank @Email String emailId,
            @NotBlank @Size(min = 8, max = 64) String password,
            @Size(max = 255) String address,
            @Pattern(regexp = "[A-Z]{5}\\d{4}[A-Z]", message = "must be a valid PAN like ABCDE1234F") String pan) {
    }

    /** username = mobile number or email. */
    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record UpdateUserRequest(
            @NotBlank @Size(max = 100) String fullName,
            @NotBlank @Email String emailId,
            @Size(max = 255) String address) {
    }

    public record UserResponse(Long userId, String fullName, String mobileNo, String emailId, String address,
                               String pan, LocalDateTime createdDate) {
        public static UserResponse of(User u) {
            return new UserResponse(u.getUserId(), u.getUserFullName(), u.getUserMobileNo(), u.getUserEmailId(),
                    u.getUserAddress(), u.getUserPan(), u.getCreatedDate());
        }
    }

    public record AuthResponse(String token, UserResponse user) {
    }

    /** Public view used to confirm the receiver before sending money. */
    public record UserLookupResponse(Long userId, String fullName) {
    }
}
