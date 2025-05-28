package now.calypso.backend.modules;

import com.rpl.rama.Depot;
import com.rpl.rama.PState;
import com.rpl.rama.Path;
import com.rpl.rama.RamaModule;
import com.rpl.rama.SubSource;
import com.rpl.rama.module.StreamTopology;

import now.calypso.backend.data.AddAuthCode;
import now.calypso.backend.data.RemoveAuthCode;

import static now.calypso.backend.CalypsoHelpers.*;

public class Relationships implements RamaModule {

    private void declareStreamTopology(Topologies topologies) {
        StreamTopology stream = topologies.stream("relationshipsStream");

        stream.pstate(
            "$$authCodeToAccountId",
            PState.mapSchema(String.class, Long.class)
        );

        stream.source("*authCodeDepot").out("*data")
            .subSource("*data",
                SubSource.create(AddAuthCode.class)
                    .macro(extractFields("*data", "*code", "*accountId"))
                    .localTransform(
                        "$$authCodeToAccountId",
                        Path.key("*code").termVal("*accountId")
                    ),
                SubSource.create(RemoveAuthCode.class)
                    .macro(extractFields("*data", "*code"))
                    .localTransform(
                        "$$authCodeToAccountId",
                        Path.key("*code").termVoid()
                    )
            );
    }

    @Override
    public void define(Setup setup, Topologies topologies) {
       setup.declareDepot("*authCodeDepot", Depot.hashBy(ExtractCode.class));

       declareStreamTopology(topologies);
    }
    
}
