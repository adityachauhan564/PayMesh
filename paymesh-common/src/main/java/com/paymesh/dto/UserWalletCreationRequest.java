package com.paymesh.dto;

/** Published on user creation and update; wallet service keeps owner name/email for notifications. */
public record UserWalletCreationRequest(Long userId, String userName, String userEmailId) {
}
