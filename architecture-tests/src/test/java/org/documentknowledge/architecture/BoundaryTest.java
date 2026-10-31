package org.documentknowledge.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.*;

class BoundaryTest {
    private static final String[] DOMAINS = {"documentmanagement", "knowledgebase", "knowledgesearch", "browserbff"};

    @Test
    void productionDependenciesPointInwardAndNeverAcrossServices() {
        JavaClasses classes = new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("org.documentknowledge");
        assertFalse(classes.isEmpty(), "Architecture verification must inspect implemented classes");
        for (String service : DOMAINS) {
            assertTrue(classes.stream().anyMatch(type -> type.getName().equals("org.documentknowledge." + service + ".bootstrap.Application")),
                    "Architecture classpath must include " + service + "; duplicate Gradle component coordinates must not substitute sibling services");
        }
        domainPurity().check(classes);
        noClasses().that().resideInAPackage("org.documentknowledge.identity..")
                .should().dependOnClassesThat().resideOutsideOfPackages("java..", "org.documentknowledge.identity..")
                .check(classes);
        applicationDirection().check(classes);
        noClasses().that().resideInAPackage("org.documentknowledge.browserbff..")
                .should().dependOnClassesThat().resideInAnyPackage("java.sql..", "jakarta.persistence..",
                        "javax.persistence..", "org.hibernate..", "org.postgresql..", "org.neo4j..")
                .allowEmptyShould(true).check(classes);
        noClasses().that().resideInAPackage("..adapters.inbound..")
                .should().dependOnClassesThat().resideInAnyPackage("..adapters.outbound..", "..bootstrap..")
                .allowEmptyShould(true).check(classes);
        noClasses().that().resideInAPackage("..adapters.outbound..")
                .should().dependOnClassesThat().resideInAnyPackage("..adapters.inbound..", "..bootstrap..")
                .allowEmptyShould(true).check(classes);
        for (String service : DOMAINS) {
            for (String sibling : DOMAINS) {
                if (!service.equals(sibling)) {
                    noClasses().that().resideInAPackage("org.documentknowledge." + service + "..")
                            .should().dependOnClassesThat().resideInAPackage("org.documentknowledge." + sibling + "..")
                            .allowEmptyShould(true).check(classes);
                }
            }
        }
    }

    @Test
    void negativeFixturesProveFrameworkAndAdapterLeaksAreRejected() {
        JavaClasses fixtures = new ClassFileImporter().importPackages("org.documentknowledge.architecturefixtures");
        assertTrue(domainPurity().evaluate(fixtures).hasViolation(), "Framework leak fixture must fail");
        assertTrue(applicationDirection().evaluate(fixtures).hasViolation(), "Adapter leak fixture must fail");
    }

    private static ArchRule domainPurity() {
        return noClasses().that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage("io.micronaut..", "jakarta..", "javax.persistence..", "org.hibernate..", "org.neo4j..", "java.net..", "java.sql..", "..application..", "..adapters..", "..bootstrap..")
                .allowEmptyShould(true);
    }

    private static ArchRule applicationDirection() {
        return noClasses().that().resideInAPackage("..application..")
                .should().dependOnClassesThat().resideInAnyPackage("io.micronaut..", "jakarta..", "org.hibernate..", "org.neo4j..", "java.net..", "java.sql..", "..adapters..", "..bootstrap..")
                .allowEmptyShould(true);
    }
}
