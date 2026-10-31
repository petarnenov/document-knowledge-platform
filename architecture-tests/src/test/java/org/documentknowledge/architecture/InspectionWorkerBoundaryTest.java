package org.documentknowledge.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class InspectionWorkerBoundaryTest {
    @Test
    void isolatedInspectorHasNoBusinessStorageOrOutboundClientDependencies() {
        var classes = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("org.documentknowledge.inspector");
        assertFalse(classes.isEmpty(), "The real inspector must be on the architecture classpath");
        noClasses().that().resideInAPackage("org.documentknowledge.inspector..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.documentknowledge.documentmanagement..",
                "org.documentknowledge.knowledgebase..",
                "org.documentknowledge.knowledgesearch..",
                "org.documentknowledge.browserbff..",
                "org.documentknowledge.authorization..",
                "software.amazon.awssdk..", "java.sql..", "javax.sql..", "java.net.http..")
            .because("untrusted structural inspection has no business, database, object-store or outbound HTTP authority")
            .check(classes);
    }
}
