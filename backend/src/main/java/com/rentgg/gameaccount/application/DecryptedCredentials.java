package com.rentgg.gameaccount.application;

/** Plaintext credentials, only ever held in memory for the renter of a valid order. */
public record DecryptedCredentials(String username, String password, int version) {

    @Override
    public String toString() {
        return "DecryptedCredentials[username=***, password=***, version=" + version + "]";
    }
}
