package now.calypso.backendapi.prompts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import now.calypso.backend.data.PromptDefinition;

class PromptLibraryTest {

    @Test
    void privatePromptsCoverEverySilhouetteDomain() {
        Set<String> covered = new LinkedHashSet<>();
        for (PromptDefinition prompt : PromptLibrary.privateBank()) {
            covered.addAll(PromptLibrary.silhouetteDomains(prompt));
        }

        for (String domain : PromptLibrary.silhouetteDomainPriority()) {
            assertTrue(covered.contains(domain), "Missing private prompt coverage for " + domain);
        }
    }

    @Test
    void resonancePromptsRouteAbstractDomainsToSilhouette() {
        PromptDefinition formative = PromptLibrary.getById("private.formative.imprints");
        assertTrue(PromptLibrary.signalDomains(formative).contains("nostalgia"));
        assertTrue(PromptLibrary.silhouetteDomains(formative).contains("formative_imprints"));

        PromptDefinition pull = PromptLibrary.getById("private.gravitational.pull");
        assertFalse(PromptLibrary.signalDomains(pull).contains("attraction"));
        assertTrue(PromptLibrary.silhouetteDomains(pull).contains("spark_archetypes"));

        PromptDefinition repair = PromptLibrary.getById("private.repair.rhythm");
        assertFalse(PromptLibrary.signalDomains(repair).contains("repair"));
        assertTrue(PromptLibrary.silhouetteDomains(repair).contains("sustainability_needs"));
        assertTrue(PromptLibrary.silhouetteDomains(repair).contains("anti_patterns"));
    }

    @Test
    void formativePromptSeparatesReferenceFromFeeling() {
        PromptDefinition formative = PromptLibrary.getById("private.formative.imprints");
        String text = formative.getText();

        assertEquals(2, text.chars().filter(ch -> ch == '?').count());
        assertTrue(text.startsWith("What things from growing up"));
        assertTrue(text.contains("What do they bring back for you?"));
        assertFalse(text.contains("game, show, book, toy, website, place"));
    }

    @Test
    void privatePromptsKeepSignalOnlyPromptsAllowedButTaggedWhenRelevant() {
        PromptDefinition politics = PromptLibrary.getById("private.political.issues");
        assertTrue(PromptLibrary.signalDomains(politics).contains("values"));
        assertFalse(PromptLibrary.silhouetteDomains(politics).contains("formative_imprints"));
    }
}
