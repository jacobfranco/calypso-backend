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
            IndexedAccountWithId.class,
            RemoveAuthCode.class,
        };
        for (int i = 0; i < classes.length; i++) ret.put(i, classes[i]);
        return ret;
    }
    
}
