package now.calypso.backend.serialization;

import java.util.*;

public class CalypsoSerialization extends ThriftSerialization {
    @Override
    protected Map<Integer, Class> typeIds() {
        Map<Integer, Class> ret = new HashMap<>();
        Class[] classes = {

        };
        for (int i = 0; i < classes.length; i++) ret.put(i, classes[i]);
        return ret;
    }
    
}
