package io.github.markpollack.journal.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import io.github.markpollack.journal.architecture.control.ExpectedValueFromDataBinding;
import io.github.markpollack.journal.architecture.control.ExpectedValueFromTheWritersParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static io.github.markpollack.journal.architecture.ArchitectureComponents.TCK;
import static io.github.markpollack.journal.architecture.ArchitectureComponents.WRITER;
import static io.github.markpollack.journal.architecture.ArchitectureComponents.topLevel;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The writer/checker boundary as executable rules over the compiled production classes of both modules (declared policy,
 * checked against extracted structure). Maven already keeps the writer off the TCK's classpath; these rules state the boundary
 * in the code itself, cover what Maven cannot see inside one module, and keep holding if a build file changes.
 * <ul>
 * <li>The TCK uses only the JDK, Jackson as a neutral parser, and itself: no writer, capture or SDK class
 * (the structural part of RULE-29; the semantic part, independently grounded expectations, is the reviewed examples' job).</li>
 * <li>The oracle core (the {@code tck.ocsf} package apart from its command-line tool) reads JSON with the neutral tokenizer
 * only and never uses the prototype candidate-production support, which lives in the same module.</li>
 * <li>The writer uses only the JDK, Jackson's tokenizer and itself: no TCK, vendor type or data binding.</li>
 * <li>The TCK's packages are free of cycles, so the neutral JSON and check ledger stay below the oracle.</li>
 * </ul>
 * Each rule's selection is asserted to hold the intended classes, so a renamed package cannot pass vacuously, and two
 * negative controls in test sources ({@code architecture.control}) show the conditions reject an otherwise valid forbidden
 * dependency. Scope limits: direct bytecode dependencies only; reflection, service loading, process invocation and reuse
 * through data are outside what these rules can see. Run in the reactor ({@code ./mvnw verify}) so the TCK classes analyzed
 * are this build's, not an installed snapshot.
 */
class ArchitectureBoundaryTest {

    static final JavaClasses SCOPE = ArchitectureComponents.importScope();

    static final DescribedPredicate<JavaClass> CHECKER = resideInAPackage(TCK + "..").as("the TCK's production classes");

    static final DescribedPredicate<JavaClass> ORACLE_CORE = DescribedPredicate.describe("the oracle core (" + TCK + ".ocsf, apart from its command-line tool)",
            c -> c.getPackageName().equals(TCK + ".ocsf") && !topLevel(c).getSimpleName().endsWith("Tool"));

    static final DescribedPredicate<JavaClass> WRITER_CODE = resideInAPackage(WRITER + "..").as("the writer's production classes");

    static ArchRule checkerUsesNoProducerCode(DescribedPredicate<? super JavaClass> checker) {
        return classes().that(checker).should().onlyDependOnClassesThat(resideInAnyPackage("java..", "com.fasterxml.jackson.core..", "com.fasterxml.jackson.databind..", "com.networknt.schema..",
                TCK + ".."))
                .because("the independent TCK must not call production capture parsers, SDK deserializers or mappers to compute expected values (RULE-29); the JSON Schema "
                        + "validator only judges protocol objects a package carries against the pinned definitions the package declares");
    }

    static ArchRule oracleCoreReadsNeutrally(DescribedPredicate<? super JavaClass> oracleCore) {
        return noClasses().that(oracleCore).should().dependOnClassesThat(resideInAnyPackage("com.fasterxml.jackson.databind..", TCK + ".ocsf.prototype.."))
                .because("expected values come from the native bytes through the neutral tokenizer, never from data binding or from the support that produces candidate packages");
    }

    static ArchRule writerUsesOnlyItselfAndTheTokenizer(DescribedPredicate<? super JavaClass> writer) {
        return classes().that(writer).should().onlyDependOnClassesThat(resideInAnyPackage("java..", "com.fasterxml.jackson.core..", WRITER + ".."))
                .because("the writer keeps its own lexically exact JSON tree, uses no vendor type or data binding, and never calls the checker that judges it");
    }

    @Test
    void eachRuleSelectsTheIntendedClasses() {
        assertThat(names(CHECKER)).contains(TCK + ".ocsf.OcsfOracle", TCK + ".ocsf.PackageReader", TCK + ".ocsf.CodexStreamProfile", TCK + ".verify.NeutralJson");
        assertThat(SCOPE.stream().filter(c -> c.isAssignableTo(TCK + ".ocsf.SourceProfile") && !c.isInterface()).count()).as("source profiles").isGreaterThanOrEqualTo(6);
        assertThat(names(ORACLE_CORE)).contains(TCK + ".ocsf.OcsfOracle", TCK + ".ocsf.ClaudeStreamProfile", TCK + ".ocsf.CodexStreamProfile", TCK + ".ocsf.TrancheReading", TCK + ".ocsf.ExpectedFacts")
                .doesNotContain(TCK + ".ocsf.OcsfReaderTool");
        assertThat(names(WRITER_CODE)).contains(WRITER + ".OcsfRunWriter", WRITER + ".CoreWriter", WRITER + ".CodexStreamMapper", WRITER + ".Jv");
        assertThat(SCOPE.stream().filter(c -> c.isAssignableTo(WRITER + ".SourceMapper") && !c.isInterface()).count()).as("source mappers").isGreaterThanOrEqualTo(5);
        assertThat(SCOPE.stream().map(JavaClass::getName)).as("test sources, the controls included, are outside the analyzed scope")
                .doesNotContain(getClass().getName(), ExpectedValueFromTheWritersParser.class.getName(), ExpectedValueFromDataBinding.class.getName());
    }

    @Test
    void theCheckerDependsOnNoProducerCode() {
        checkerUsesNoProducerCode(CHECKER).check(SCOPE);
    }

    @Test
    void theOracleCoreReadsNativeBytesNeutrally() {
        oracleCoreReadsNeutrally(ORACLE_CORE).check(SCOPE);
    }

    @Test
    void theWriterDependsOnlyOnItselfAndTheJacksonTokenizer() {
        writerUsesOnlyItselfAndTheTokenizer(WRITER_CODE).check(SCOPE);
    }

    @Test
    void theCheckersPackagesAreFreeOfCycles() {
        slices().matching(TCK + ".(**)").should().beFreeOfCycles().check(SCOPE);
    }

    @Test
    void aCheckerReadingNativeLinesWithTheWritersParserIsRejected() {
        EvaluationResult result = checkerUsesNoProducerCode(only(ExpectedValueFromTheWritersParser.class)).evaluate(control(ExpectedValueFromTheWritersParser.class));
        assertThat(result.hasViolation()).isTrue();
        assertThat(String.join("\n", result.getFailureReport().getDetails())).contains(WRITER + ".Jv.parse");
    }

    @Test
    void anOracleBindingNativeLinesWithDataBindingIsRejected() {
        EvaluationResult result = oracleCoreReadsNeutrally(only(ExpectedValueFromDataBinding.class)).evaluate(control(ExpectedValueFromDataBinding.class));
        assertThat(result.hasViolation()).isTrue();
        assertThat(String.join("\n", result.getFailureReport().getDetails())).contains("com.fasterxml.jackson.databind.ObjectMapper");
    }

    private static List<String> names(DescribedPredicate<JavaClass> selection) {
        return SCOPE.stream().filter(selection).map(JavaClass::getName).toList();
    }

    private static JavaClasses control(Class<?> control) {
        return new ClassFileImporter().importClasses(control);
    }

    private static DescribedPredicate<JavaClass> only(Class<?> control) {
        return DescribedPredicate.describe("the negative control " + control.getSimpleName(), c -> topLevel(c).getName().equals(control.getName()));
    }
}
