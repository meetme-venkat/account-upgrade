package com.mercur.upgrade.guardrails;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.util.ClassUtils;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Code guardrail: a class that implements one of the application's own interfaces (a port such as
 * {@code ProcessedUpgradeRepository} or {@code EmailSender}) is named {@code <Name>Impl} and lives in an
 * {@code impl} package of its module, e.g. {@code persistence.impl.ProcessedUpgradeRepositoryImpl}.
 * Conversely, {@code impl} packages and {@code *Impl} classes hold nothing else.
 * Classes implementing only framework interfaces (Spring, Kafka, servlet) keep their framework-style names.
 */
class ImplementationNamingConventionTest {

    private static final String BASE_PACKAGE = "com.mercur.upgrade";

    private static List<Class<?>> mainClasses;

    @BeforeAll
    static void scanMainClasses() {
        // Every named top-level or static nested class, not only Spring components.
        var scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition definition) {
                return definition.getMetadata().isIndependent();
            }
        };
        scanner.addIncludeFilter((reader, factory) -> true);
        mainClasses = scanner.findCandidateComponents(BASE_PACKAGE).stream()
                .map(BeanDefinition::getBeanClassName)
                .map(ImplementationNamingConventionTest::load)
                .filter(ImplementationNamingConventionTest::isMainCode)
                .toList();
    }

    @Test
    void scansTheApplicationClasses() {
        assertThat(mainClasses).hasSizeGreaterThan(20);
    }

    @Test
    void implementationsOfApplicationInterfacesAreNamedImplInAnImplPackage() {
        List<String> violations = mainClasses.stream()
                .filter(c -> !c.isInterface() && implementsApplicationInterface(c))
                .filter(c -> !c.getSimpleName().endsWith("Impl") || !c.getPackageName().endsWith(".impl"))
                .map(c -> c.getName() + " implements " + applicationInterfaces(c).toList()
                        + ": rename it to <Name>Impl and move it to an 'impl' package")
                .toList();

        assertThat(violations).isEmpty();
    }

    @Test
    void implPackagesAndImplClassesContainOnlyImplementations() {
        List<String> violations = mainClasses.stream()
                .filter(c -> c.getPackageName().endsWith(".impl") || c.getSimpleName().endsWith("Impl"))
                .filter(c -> !implementsApplicationInterface(c)
                        || !c.getSimpleName().endsWith("Impl") || !c.getPackageName().endsWith(".impl"))
                .map(c -> c.getName() + ": only <Name>Impl classes implementing an application interface belong here")
                .toList();

        assertThat(violations).isEmpty();
    }

    private static boolean implementsApplicationInterface(Class<?> type) {
        return applicationInterfaces(type).findAny().isPresent();
    }

    private static Stream<String> applicationInterfaces(Class<?> type) {
        return ClassUtils.getAllInterfacesForClassAsSet(type).stream()
                .map(Class::getName)
                .filter(name -> name.startsWith(BASE_PACKAGE + "."))
                .sorted();
    }

    /** Production classes only: test doubles implementing ports (e.g. in-test repositories) are exempt. */
    private static boolean isMainCode(Class<?> type) {
        var source = type.getProtectionDomain().getCodeSource();
        return source != null && !source.getLocation().getPath().contains("/test-classes/");
    }

    private static Class<?> load(String className) {
        try {
            return ClassUtils.forName(className, ImplementationNamingConventionTest.class.getClassLoader());
        }
        catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }
}
