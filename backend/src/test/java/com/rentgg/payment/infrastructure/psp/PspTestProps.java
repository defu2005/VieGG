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
        return of(payos, new Payment.Momo("", "", "", ""), new Payment.Zalopay("", "", "", ""),
                new Payment.Vnpay("", "", ""));
    }

    static RentGgProperties withMomo(Payment.Momo momo) {
        return of(new Payment.Payos("", "", "", ""), momo, new Payment.Zalopay("", "", "", ""),
                new Payment.Vnpay("", "", ""));
    }

    static RentGgProperties withZalopay(Payment.Zalopay zalopay) {
        return of(new Payment.Payos("", "", "", ""), new Payment.Momo("", "", "", ""), zalopay,
                new Payment.Vnpay("", "", ""));
    }

    static RentGgProperties withVnpay(Payment.Vnpay vnpay) {
        return of(new Payment.Payos("", "", "", ""), new Payment.Momo("", "", "", ""),
                new Payment.Zalopay("", "", "", ""), vnpay);
    }

    static RentGgProperties of(Payment.Payos payos, Payment.Momo momo, Payment.Zalopay zalopay, Payment.Vnpay vnpay) {
        return new RentGgProperties(new Order(15, 30, 10, 10), new Rotation(60),
                new Credentials(10, true, "", ""),
                new Security("", Duration.ofMinutes(15), Duration.ofDays(14), "", true),
                new RateLimit(5, 20, 10, 5, 20), new Approval(Duration.ofMinutes(30)),
                new Dispute(Duration.ofHours(72), Duration.ofHours(4)), new Jobs(false, "0 * * * * *", 100),
                new Outbox(10),
                new Payment("PAYOS", "", "https://rentgg.test", "https://rentgg.test", false,
                        vnpay, momo, zalopay, payos));
    }
}
