package now.calypso.backend.serialization;

import java.util.*;

import now.calypso.backend.data.*;

public class CalypsoSerialization extends ThriftSerialization {
    @Override
    protected Map<Integer, Class> typeIds() {
        Map<Integer, Class> ret = new HashMap<>();
        Class[] classes = {
                Account.class,
                AccountWithId.class,
                AddAuthCode.class,
                Attachment.class,
                AttachmentWithId.class,
                CursorAck.class,
                Filters.class,
                IndexedAccountWithId.class,
                LocationFilter.class,
                ManyToManyFilter.class,
                MatchCandidate.class,
                MatchRefillRequest.class,
                ModeFilter.class,
                OneToManyFilter.class,
                RangeFilter.class,
                RemoveAuthCode.class,
                ServedPairs.class,
                Signals.class,
                SignalRecord.class,
                PromptQuestion.class,
                PromptResponse.class,
                PromptState.class,
                AgentMessage.class,
                AgentSession.class,
                TagPreference.class,

        };
        for (int i = 0; i < classes.length; i++)
            ret.put(i, classes[i]);
        return ret;
    }

}
