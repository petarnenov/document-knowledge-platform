package org.documentknowledge.architecture;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.*;

class LoggingClientBoundaryTest {
    private static final ArchRule ADAPTER_ONLY=noClasses().that()
        .resideInAnyPackage("org.documentknowledge.loggingclient..","org.documentknowledge.loggingclientfixtures..")
        .should().dependOnClassesThat().resideOutsideOfPackages("java..","javax.net.ssl..","tools.jackson..","com.sun.security.auth.module",
            "org.documentknowledge.loggingclient..","org.documentknowledge.loggingclientfixtures..","org.documentknowledge.observability..");
    @Test void producerIsAnAdapterWithoutServiceOrFrameworkImports() {
        var classes=new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests()).importPackages("org.documentknowledge.loggingclient");
        assertTrue(classes.stream().anyMatch(type->type.getSimpleName().equals("LogProducer")));
        ADAPTER_ONLY.check(classes);
        assertTrue(classes.stream().flatMap(type->type.getDirectDependenciesFromSelf().stream())
            .map(dependency->dependency.getTargetClass()).filter(type->type.getPackageName().startsWith("com.sun"))
            .allMatch(type->type.getName().equals("com.sun.security.auth.module.UnixSystem")));
    }
    @Test void serviceLeakIsRejected() {
        var fixture=new ClassFileImporter().importPackages("org.documentknowledge.loggingclientfixtures");
        assertFalse(fixture.isEmpty());assertTrue(ADAPTER_ONLY.evaluate(fixture).hasViolation());
    }
}
