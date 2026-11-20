package com.rentgg.docs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("prod")
class ProdApiDocsDisabledIT extends ApiDocsDisabledTest {

    @Test
    void docsAreNotServedOnProd() throws Exception {
        assertDocsAreNotServed();
    }
}
