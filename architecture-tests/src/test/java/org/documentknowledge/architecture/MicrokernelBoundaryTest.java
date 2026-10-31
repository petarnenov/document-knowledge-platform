package org.documentknowledge.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Static enforcement of System Design 5.1-5.4, executed by architectureTest.
 * Plugin packages use plugin.spi (contracts), plugins.<id> (implementations),
 * and microkernel (registration/orchestration). These tests do not establish
 * runtime signature verification, lifecycle authorization or production approval.
 */
class MicrokernelBoundaryTest {
    private static final String[] OWNED_DOMAINS = {
        "..documentmanagement..", "..knowledgebase..", "..knowledgesearch..", "..browserbff.."
    };

    @Test
    void implementedProductionClassesRespectMicrokernelBoundaries() {
        JavaClasses production = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("org.documentknowledge");
        assertFalse(production.isEmpty(), "Architecture checks must inspect production classes");
        rules().forEach(rule -> rule.check(production));
    }

    @Test
    void pluginSourcesCannotSilentlyFallOutsideTheArchitectureClasspath() throws IOException {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("settings.gradle"))) root = root.getParent();
        assertTrue(root != null, "Architecture verification requires the repository root");
        JavaClasses production = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("org.documentknowledge");
        assertPluginSourceCoverage(root, production);
    }

    @Test
    void classpathAndPackageGuardsHavePositiveAndNegativeFixtures(@TempDir Path root) throws IOException {
        JavaClasses valid = new ClassFileImporter().importPackages("org.documentknowledge.microkernelvalid");
        Path source = root.resolve("modules/plugin-spi/src/main/java/Capability.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package org.documentknowledge.microkernelvalid.plugin.spi;\n");
        assertPluginSourceCoverage(root, valid);
        Files.writeString(source, "package unrelated.unguarded;\n");
        assertThrows(AssertionError.class, () -> assertPluginSourceCoverage(root, valid));
        Files.writeString(source, "package org.documentknowledge.missing.plugin.spi;\n");
        assertThrows(AssertionError.class, () -> assertPluginSourceCoverage(root, valid));
    }

    @Test
    void approvedDependencyShapeIsAccepted() {
        JavaClasses valid = new ClassFileImporter().importPackages("org.documentknowledge.microkernelvalid");
        assertFalse(valid.isEmpty(), "Positive fixtures must be imported");
        rules().forEach(rule -> rule.check(valid));
    }

    @Test
    void eachGuardRejectsAnActualForbiddenDependencyOrContract() {
        JavaClasses invalid = new ClassFileImporter().importPackages("org.documentknowledge.microkernelfixtures");
        assertFalse(invalid.isEmpty(), "Negative fixtures must be imported");
        for (ArchRule rule : rules()) {
            assertTrue(rule.evaluate(invalid).hasViolation(),
                () -> "Negative fixture must fail: " + rule.getDescription());
        }
    }

    private static List<ArchRule> rules() {
        return List.of(
            noClasses().that().resideInAPackage("..plugin.spi..")
                .should().dependOnClassesThat().resideOutsideOfPackages(
                    "java..", "..plugin.spi..", "org.documentknowledge.identity..",
                    "org.documentknowledge.contracts..", "org.documentknowledge.artifactcontracts..")
                .because("SPI consists of stable technical contracts, never framework, domain or implementation types")
                .allowEmptyShould(true),
            fields().that().areDeclaredInClassesThat().resideInAPackage("..plugin.spi..")
                .should().beFinal()
                .because("SPI contracts must not expose assignable shared state")
                .allowEmptyShould(true),
            classes().that().resideInAPackage("..plugin.spi..")
                .should(haveTypedCapabilitySignatures()).allowEmptyShould(true),
            noClasses().that().resideInAnyPackage("..plugins..", "..microkernel..")
                .should().dependOnClassesThat().resideInAnyPackage(OWNED_DOMAINS)
                .because("plugins and the microkernel must not reach into service-owned implementations or policy")
                .allowEmptyShould(true),
            noClasses().that().resideInAPackage("..microkernel..")
                .should().dependOnClassesThat().resideInAPackage("..plugins..")
                .because("the microkernel depends on SPI; bootstrap assembles installed implementations")
                .allowEmptyShould(true),
            slices().matching("..plugins.(*)..").should().notDependOnEachOther()
                .because("plugins communicate through approved contracts rather than concrete sibling implementations")
                .allowEmptyShould(true),
            noClasses().that().resideInAnyPackage("..plugin.spi..", "..plugins..", "..microkernel..", "..adapters.inbound..")
                .should().dependOnClassesThat().haveFullyQualifiedName("java.net.URLClassLoader")
                .because("requests and plugin registration must not load arbitrary downloaded code")
                .allowEmptyShould(true),
            noClasses().that().resideInAnyPackage("..plugin.spi..", "..plugins..", "..microkernel..", "..adapters.inbound..")
                .should().callMethodWhere(DescribedPredicate.describe("load code from a class name or bytecode", call -> {
                    String owner = call.getTarget().getOwner().getName();
                    String name = call.getTarget().getName();
                    return (owner.equals("java.lang.Class") && name.equals("forName"))
                        || (call.getTarget().getOwner().isAssignableTo(ClassLoader.class)
                            && List.of("loadClass", "defineClass", "findClass").contains(name))
                        || (owner.equals("java.lang.invoke.MethodHandles$Lookup")
                            && List.of("defineClass", "defineHiddenClass", "defineHiddenClassWithClassData").contains(name));
                }))
                .because("request/configuration strings must not select executable implementation classes")
                .allowEmptyShould(true),
            noClasses().that().resideInAnyPackage("..plugin.spi..", "..microkernel..", "..adapters.inbound..", "..application..", "..domain..")
                .should().dependOnClassesThat().haveFullyQualifiedName("java.lang.ProcessBuilder")
                .because("native tools belong in reviewed isolated outbound workers, never request handlers or the kernel")
                .allowEmptyShould(true),
            noClasses().that().resideInAnyPackage("..plugin.spi..", "..microkernel..", "..adapters.inbound..")
                .should().callMethodWhere(DescribedPredicate.describe("execute an operating-system command", call ->
                    call.getTarget().getOwner().isEquivalentTo(Runtime.class) && call.getTarget().getName().equals("exec")))
                .because("request/configuration strings must not become shell commands")
                .allowEmptyShould(true)
        );
    }

    private static ArchCondition<JavaClass> haveTypedCapabilitySignatures() {
        return new ArchCondition<>("declare typed capability parameters and results") {
            @Override
            public void check(JavaClass type, ConditionEvents events) {
                // Records legitimately synthesize equals(Object); capability SPIs
                // are interfaces, while their data types are checked separately.
                if (!type.isInterface()) return;
                type.getMethods().forEach(method -> {
                    boolean untyped = method.getRawReturnType().isEquivalentTo(Object.class)
                        || method.getRawParameterTypes().stream().anyMatch(parameter -> parameter.isEquivalentTo(Object.class));
                    if (untyped) {
                        events.add(SimpleConditionEvent.violated(method,
                            method.getFullName() + " exposes an untyped Object capability contract"));
                    }
                });
            }
        };
    }

    private static void assertPluginSourceCoverage(Path root, JavaClasses imported) throws IOException {
        Pattern declaration = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
        for (String area : List.of("modules", "services", "workers", "plugins")) {
            Path directory = root.resolve(area);
            if (!Files.isDirectory(directory)) continue;
            try (var paths = Files.walk(directory)) {
                for (Path source : paths.filter(path -> path.toString().replace('\\', '/').contains("/src/main/java/"))
                        .filter(path -> path.toString().endsWith(".java"))
                        .filter(path -> !List.of("package-info.java", "module-info.java").contains(path.getFileName().toString()))
                        .toList()) {
                    var matcher = declaration.matcher(Files.readString(source));
                    String packageName = matcher.find() ? matcher.group(1) : "";
                    boolean guardedPackage = packageName.matches(".*\\.plugin\\.spi(?:\\..*)?")
                        || packageName.matches(".*\\.plugins\\.[^.]+(?:\\..*)?")
                        || packageName.matches(".*\\.microkernel(?:\\..*)?");
                    boolean pluginDirectory = area.equals("plugins")
                        || source.startsWith(root.resolve("modules/plugin-spi"))
                        || source.startsWith(root.resolve("modules/microkernel"));
                    if (!pluginDirectory && !guardedPackage) continue;
                    assertTrue(guardedPackage, () -> source + " must use a guarded SPI/plugin/microkernel package");
                    String className = packageName + "." + source.getFileName().toString().replaceFirst("\\.java$", "");
                    assertTrue(imported.contain(className),
                        () -> "Architecture classpath omits plugin source " + className
                            + "; register its module as an architecture-tests dependency");
                }
            }
        }
    }
}
