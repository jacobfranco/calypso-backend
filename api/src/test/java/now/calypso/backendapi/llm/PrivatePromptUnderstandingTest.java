package now.calypso.backendapi.llm;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.List;

import org.junit.jupiter.api.Test;

class PrivatePromptUnderstandingTest {
    @Test
    void buildUserPrompt_preservesLateDetailsFromRichFormativeAnswers() throws Exception {
        String answer = "Okami and Katamari Damacy made me interested in Asian culture and playful surreal aesthetics. "
                + "The old computer games felt vivid and strange in a way that stuck with me. ".repeat(5)
                + "Carmen Sandiego made me want to travel internationally, be like a secret agent, "
                + "and experience the mundane normal life in other countries.";
        assertTrue(answer.indexOf("mundane normal life") > 360);

        Method method = PrivatePromptUnderstanding.class.getDeclaredMethod(
                "buildUserPrompt",
                String.class,
                String.class,
                String.class,
                String.class,
                java.util.Collection.class,
                java.util.Collection.class);
        method.setAccessible(true);
        String prompt = (String) method.invoke(
                null,
                "private.formative.imprints",
                "Extract exact formative media titles.",
                "What things from growing up still have a hold on you?",
                answer,
                List.of(),
                List.of());

        assertTrue(prompt.contains("mundane normal life in other countries"));
    }
}
