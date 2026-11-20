package com.rentgg.payment.infrastructure.psp;

import com.rentgg.config.RentGgProperties;
import com.rentgg.config.RentGgProperties.Approval;
import com.rentgg.config.RentGgProperties.Credentials;
import com.rentgg.config.RentGgProperties.Dispute;
import com.rentgg.config.RentGgProperties.Jobs;
import com.rentgg.config.RentGgProperties.Order;
import com.rentgg.config.RentGgProperties.Outbox;
import com.rentgg.config.RentGgProperties.Payment;
import com.rentgg.config.RentGgProperties.RateLimit;
import com.rentgg.config.RentGgProperties.Rotation;
import com.rentgg.config.RentGgProperties.Security;

import java.time.Duration;

/** Properties for a gateway built directly in a contract test: only the PSP block matters. */
final class PspTestProps {

    private PspTestProps() {}

    static RentGgProperties withPayos(Payment.Payos payos) {
        return new RentGgProperties(new Order(15, 30, 10, 10), new Rotation(60),
                new Credentials(10, true, "", ""),
                new Security("", Duration.ofMinutes(15), Duration.ofDays(14), "", true),
                new RateLimit(5, 20, 10, 5, 20), new Approval(Duration.ofMinutes(30)),
                new Dispute(Duration.ofHours(72), Duration.ofHours(4)), new Jobs(false, "0 * * * * *", 100),
                new Outbox(10),
                new Payment("PAYOS", "", "https://rentgg.test", "https://rentgg.test", false,
                        new Payment.Vnpay("", "", ""), new Payment.Momo("", "", "", ""),
                        new Payment.Zalopay("", "", "", ""), payos));
    }
}
