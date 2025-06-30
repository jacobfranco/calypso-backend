package now.calypso.backend;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.thrift.TBase;
import org.apache.thrift.TFieldIdEnum;
import org.apache.thrift.TUnion;

import com.rpl.rama.Block;
import com.rpl.rama.Helpers;

import now.calypso.backend.ops.ExtractField;

public class CalypsoHelpers {

    public static final ConcurrentHashMap<Class, Map<String, TFieldIdEnum>> TFIELD_CACHE = new ConcurrentHashMap<>();

    public static class ExtractCode extends ExtractField {
        public ExtractCode() {
            super("code");
        }
    }

    public static class ExtractEmail extends ExtractField {
        public ExtractEmail() {
            super("email");
        }
    }

    public static class ExtractAccountId extends ExtractField {
        public ExtractAccountId() {
            super("accountId");
        }
    }

    public static Block extractFields(Object from, String... fieldVars) {
        Block.Impl ret = Block.create();
        for (String f : fieldVars) {
            String name;
            if (Helpers.isGeneratedVar(f))
                name = Helpers.getGeneratedVarPrefix(f);
            else
                name = f.substring(1);
            ret = ret.each(new ExtractField(name), from).out(f);
        }
        return ret;
    }

    public static Object getTFieldByName(TBase obj, String fieldName) {
        TFieldIdEnum field = getTFieldCache(obj.getClass()).get(fieldName);
        if (field == null)
            throw new RuntimeException("Field " + fieldName + " does not exist on " + obj.getClass());

        Object ret = null;
        if (obj.isSet(field))
            ret = obj.getFieldValue(field);
        if (ret instanceof TUnion)
            ret = ((TUnion) ret).getFieldValue();
        return ret;
    }

    public static Map<String, TFieldIdEnum> getTFieldCache(Class thriftClass) {
        Map<String, TFieldIdEnum> ret = TFIELD_CACHE.get(thriftClass);
        if (ret == null) {
            try {
                Field f = thriftClass.getField("metaDataMap");
                Map<TFieldIdEnum, Object> m = (Map) f.get(thriftClass);
                ret = new HashMap<>();
                for (TFieldIdEnum e : m.keySet())
                    ret.put(e.getFieldName(), e);
                TFIELD_CACHE.put(thriftClass, ret);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return ret;
    }

    public static Long parseAccountId(String id) {
        if (id == null)
            return null;
        String[] parts = id.split("-");
        if ("a".equals(parts[parts.length - 1]) && parts.length == 2)
            return Long.parseLong(parts[0]);
        else
            throw new RuntimeException("Not an account id: " + id);
    }

    public static String serializeAccountId(long accountId) {
        return String.format("%019d", accountId) + "-a";
    }

}
