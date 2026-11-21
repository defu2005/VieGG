package com.rentgg.docs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("staging")
class StagingApiDocsDisabledIT extends ApiDocsDisabledTest {

    @Test
    void docsAreNotServedOnStaging() throws Exception {
        assertDocsAreNotServed();
    }
}
