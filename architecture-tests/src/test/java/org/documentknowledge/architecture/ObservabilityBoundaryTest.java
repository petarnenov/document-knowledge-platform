package org.documentknowledge.architecture;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.*;

class ObservabilityBoundaryTest {
    private static final ArchRule JDK_ONLY = noClasses().that()
        .resideInAnyPackage("org.documentknowledge.observability..", "org.documentknowledge.observabilityfixtures..")
        .should().dependOnClassesThat().resideOutsideOfPackages("java..", "org.documentknowledge.observability..",
            "org.documentknowledge.observabilityfixtures..");

    @Test
    void metadataHasNoServiceOrFrameworkDependency() {
        var classes = new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("org.documentknowledge.observability");
        assertTrue(classes.stream().anyMatch(type -> type.getSimpleName().equals("EventMetadata")),
            "The production metadata module must actually be on the architecture classpath");
        JDK_ONLY.check(classes);
    }

    @Test
    void frameworkLeakFixtureIsRejected() {
        var fixtures = new ClassFileImporter().importPackages("org.documentknowledge.observabilityfixtures");
        assertFalse(fixtures.isEmpty());
        assertTrue(JDK_ONLY.evaluate(fixtures).hasViolation());
    }
}
