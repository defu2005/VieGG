package com.rentgg.gameaccount.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Computes login_fingerprint for game accounts that do not have one yet (rows created before V11). */
@Component
@Order(1)
public class LoginFingerprintBackfill implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LoginFingerprintBackfill.class);

    private final GameAccountService gameAccounts;

    public LoginFingerprintBackfill(GameAccountService gameAccounts) {
        this.gameAccounts = gameAccounts;
    }

    @Override
    public void run(ApplicationArguments args) {
        int filled = gameAccounts.backfillLoginFingerprints();
        if (filled > 0) {
            log.info("Filled login fingerprint for {} game account(s)", filled);
        }
    }
}
