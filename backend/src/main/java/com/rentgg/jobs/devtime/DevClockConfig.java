package com.rentgg.jobs.devtime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import java.nio.file.Path;
import java.time.Clock;

/** Dev profile only: replaces the system clock with a {@link DevClock} that can be moved via /api/v1/dev/time. */
@Configuration
@Profile("dev")
public class DevClockConfig {

    @Bean
    @Primary
    public DevClock devClock(@Value("${rentgg.dev.clock-file:target/dev-clock-${server.port:8080}.properties}") String file) {
        return new DevClock(Clock.systemUTC(), Path.of(file));
    }
}
