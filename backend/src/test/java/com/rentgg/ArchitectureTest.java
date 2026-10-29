package com.rentgg;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Module dependency rules of §4.4: a module never depends on a module of a higher layer, and never touches
 * another module's repositories (infrastructure) or entities' persistence.
 */
class ArchitectureTest {

    /** Layer rank per module (lower layers must not know higher ones). */
    private static final Map<String, Integer> RANK = Map.ofEntries(
            Map.entry("shared", 0), Map.entry("config", 0),
            Map.entry("audit", 1), Map.entry("notification", 1),
            Map.entry("identity", 2), Map.entry("catalog", 2), Map.entry("ledger", 2), Map.entry("settings", 2),
            Map.entry("gameaccount", 3), Map.entry("payment", 3), Map.entry("deposit", 3),
            Map.entry("listing", 4),
            Map.entry("order", 5),
            Map.entry("dispute", 6), Map.entry("wallet", 6), Map.entry("approval", 6), Map.entry("helpdesk", 6),
            Map.entry("jobs", 7));

    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.rentgg");

    @Test
    void lowerLayersDoNotDependOnHigherLayers() {
        for (var low : RANK.entrySet()) {
            List<String> higher = RANK.entrySet().stream()
                    .filter(e -> e.getValue() > low.getValue())
                    .map(e -> "com.rentgg." + e.getKey() + "..")
                    .toList();
            if (higher.isEmpty() || low.getKey().equals("config")) {
                continue;
            }
            noClasses().that().resideInAPackage("com.rentgg." + low.getKey() + "..")
                    .should().dependOnClassesThat().resideInAnyPackage(higher.toArray(String[]::new))
                    .check(classes);
        }
    }

    @Test
    void noCrossModuleRepositoryAccess() {
        for (String module : RANK.keySet()) {
            if (module.equals("jobs") || module.equals("shared") || module.equals("config")) {
                continue;
            }
            noClasses().that().resideOutsideOfPackages("com.rentgg." + module + "..", "com.rentgg.jobs..")
                    .should().dependOnClassesThat().resideInAPackage("com.rentgg." + module + ".infrastructure..")
                    .check(classes);
        }
    }
}
