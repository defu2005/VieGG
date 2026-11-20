package com.rentgg.docs;

import com.rentgg.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Control: under the test profile the same docs path is served, so the 404s above prove something. */
class ApiDocsEnabledInTestProfileIT extends IntegrationTest {

    @Test
    void docsAreServedInTestProfile() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
    }
}
