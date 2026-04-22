package now.calypso.backendapi.silhouette;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

public class SilhouettePatchTest {
    @Test
    void toMap_emitsMutableCollectionsForRamaSerialization() {
        SilhouettePatch patch = new SilhouettePatch();
        patch.ops.add(new SilhouettePatch.Op(
                "upsert_claim",
                "seeking_core",
                null,
                "Drawn to ambitious partners.",
                null,
                "preference",
                0.78,
                List.of("sig_1")));

        Map<String, Object> serialized = patch.toMap();
        assertNotNull(serialized);
        assertInstanceOf(HashMap.class, serialized);

        Object opsRaw = serialized.get("ops");
        assertNotNull(opsRaw);
        assertInstanceOf(ArrayList.class, opsRaw);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ops = (List<Map<String, Object>>) opsRaw;
        assertEquals(1, ops.size());
        assertInstanceOf(HashMap.class, ops.get(0));

        Object evidenceRaw = ops.get(0).get("evidenceIds");
        assertNotNull(evidenceRaw);
        assertInstanceOf(ArrayList.class, evidenceRaw);

        String mapType = serialized.getClass().getName();
        assertFalse(mapType.contains("ImmutableCollections"), mapType);
    }
}
