package now.calypso.backendapi.matchstandards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import now.calypso.backend.data.MatchStandardAnswer;
import now.calypso.backend.data.MatchStandardAnswerSet;
import now.calypso.backend.data.MatchStandardOption;
import now.calypso.backend.data.MatchStandardQuestion;
import now.calypso.backend.data.MatchStandardQuestionAnswerType;

class MatchStandardQuestionLibraryTest {
    @Test
    void starterQuestionsMirrorCoreOnboardingLifestylePreferences() {
        Set<String> starterIds = MatchStandardQuestionLibrary.all().stream()
                .filter(question -> question.isSetTags()
                        && question.getTags().contains(MatchStandardQuestionLibrary.TAG_STARTER))
                .map(MatchStandardQuestion::getQuestionId)
                .collect(Collectors.toSet());

        assertTrue(starterIds.contains("standard.values.politics"));
        assertTrue(starterIds.contains("standard.religion.identity"));
        assertTrue(starterIds.contains("standard.kids.future"));
        assertTrue(starterIds.contains("standard.substances.alcohol"));
        assertTrue(starterIds.contains("standard.substances.smoking"));
        assertTrue(starterIds.contains("standard.substances.cannabis"));
        assertTrue(starterIds.contains("standard.substances.drugs"));

        assertFalse(starterIds.contains("standard.values.worldview_role"));
        assertFalse(starterIds.contains("standard.kids.current"));
        assertFalse(starterIds.contains("standard.money.ambition"));
    }

    @Test
    void religionQuestionUsesDetailedReligionOptions() {
        Set<String> optionIds = optionIds("standard.religion.identity");

        assertTrue(optionIds.contains("christian"));
        assertTrue(optionIds.contains("muslim"));
        assertTrue(optionIds.contains("hindu"));
        assertTrue(optionIds.contains("spiritual"));
        assertTrue(optionIds.contains("secular_humanist"));
        assertTrue(optionIds.contains("custom_belief"));
        assertTrue(optionIds.contains("prefer_not_to_say"));
        assertFalse(optionIds.contains("religious"));
        assertFalse(optionIds.contains("prefer_not"));
    }

    @Test
    void kidsQuestionCombinesCurrentStatusAndFuturePreference() {
        MatchStandardQuestion question = MatchStandardQuestionLibrary.getById("standard.kids.future");

        assertEquals(MatchStandardQuestionAnswerType.MULTI_CHOICE, question.getAnswerType());
        Set<String> optionIds = optionIds(question);
        assertTrue(optionIds.contains("has_kids"));
        assertTrue(optionIds.contains("no_kids"));
        assertTrue(optionIds.contains("wants_kids"));
        assertTrue(optionIds.contains("open_to_kids"));
        assertTrue(optionIds.contains("doesnt_want_kids"));
    }

    @Test
    void cannabisAndRecreationalDrugsAreSeparateQuestions() {
        Set<String> cannabisOptions = optionIds("standard.substances.cannabis");
        Set<String> drugOptions = optionIds("standard.substances.drugs");

        assertTrue(cannabisOptions.contains("no_cannabis"));
        assertTrue(cannabisOptions.contains("occasional_cannabis"));
        assertFalse(cannabisOptions.contains("recreational_drugs"));

        assertTrue(drugOptions.contains("no_drugs"));
        assertTrue(drugOptions.contains("psychedelics_user"));
        assertTrue(drugOptions.contains("recreational_drugs"));
        assertFalse(drugOptions.contains("cannabis_ok"));
    }

    @Test
    void nextQuestionReturnsNullWhenAllMatchingQuestionsAreAnswered() {
        MatchStandardAnswerSet answerSet = new MatchStandardAnswerSet()
                .setAccountId(1L)
                .setAnswers(MatchStandardQuestionLibrary.all().stream()
                        .filter(question -> question.isSetTags()
                                && question.getTags().contains(MatchStandardQuestionLibrary.TAG_STARTER))
                        .map(question -> new MatchStandardAnswer()
                                .setAccountId(1L)
                                .setQuestionId(question.getQuestionId())
                                .setOwnAnswerOptionIds(List.of(question.getOptions().get(0).getOptionId()))
                                .setAcceptableAnswerOptionIds(List.of(question.getOptions().get(0).getOptionId())))
                        .toList());

        assertNull(MatchStandardQuestionLibrary.nextQuestion(answerSet, null, true));
    }

    private static Set<String> optionIds(String questionId) {
        return optionIds(MatchStandardQuestionLibrary.getById(questionId));
    }

    private static Set<String> optionIds(MatchStandardQuestion question) {
        return question.getOptions().stream()
                .map(MatchStandardOption::getOptionId)
                .collect(Collectors.toSet());
    }
}
