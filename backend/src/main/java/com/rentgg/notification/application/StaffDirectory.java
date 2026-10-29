package com.rentgg.notification.application;

import java.util.List;
import java.util.UUID;

/** Port implemented by the identity module so notification does not depend on it (§4.4). */
public interface StaffDirectory {

    List<UUID> userIdsWithRole(String role);
}
