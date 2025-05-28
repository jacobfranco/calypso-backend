package now.calypso.backend.modules;

import org.apache.thrift.protocol.TField;

import com.rpl.rama.*;
import com.rpl.rama.module.*;
import com.rpl.rama.ops.*;
import com.rpl.rama.helpers.*;

import now.calypso.backend.*;
import now.calypso.backend.data.*;

import static now.calypso.backend.CalypsoHelpers.extractFields;

import java.util.*;
import java.util.stream.Collectors;

public class Core implements RamaModule {

    /*
    Accounts require low latency updates (a few millis) so streaming is used for processing (instead
    of microbatching). Streaming integrates with depot appends as well, allowing for coordination of
    updates with the frontend. Depot appends done with AckLevel.ACK (the default) only return when
    all colocated streaming topologies have finished processing the data in that append. This is used
    in the frontend so it knows when an account update has gone through (e.g. to reload page or
    re-enable a submit button).
   */
  private static void declareAccountsTopology(Topologies topologies) {
      StreamTopology stream = topologies.stream("accounts");
      ModuleUniqueIdPState accountIdGen = new ModuleUniqueIdPState("$$accountIdGen");
      accountIdGen.declarePState(stream);
      stream.pstate("$$emailToUser", PState.mapSchema(String.class,
                                                     PState.fixedKeysSchema("accountId", Long.class,
                                                                            "uuid", String.class)));
      stream.pstate("$$accountIdToAccount", PState.mapSchema(Long.class, Account.class));

      /*
        User registration does three things when that name is not already registered:
          - generates a user id for that user
          - updates $$nameToUser PState (which contains a mapping from name -> user id)
          - updates $$accountIdToAccount PState (which maps user id to Account)

        User registration is implemented to correctly handle:
          - Concurrent registration of same name (first one wins)
          - Failures of topology (e.g. a machine involved in the processing dies midway through
            processing). Streaming failures are handled by retrying from the start of the topology.
       */
      stream.source("*accountDepot").out("*data")
            .macro(extractFields("*data", "*email", "*uuid"))
            .localSelect("$$emailToUser", Path.key("*email")).out("*currInfo")
            .each(Ops.GET, "*currInfo", "uuid").out("*currUUID")
            // By including a UUID with each registration request, we can distinguish between:
            //   - this email is already registered by a different request so we shouldn't override it
            //   - this email was registered by the same request, so we should continue finishing the
            //     registration
            .ifTrue(new Expr(Ops.OR, new Expr(Ops.IS_NULL, "*currInfo"),
                                     new Expr(Ops.EQUAL, "*uuid", "*currUUID")),
              Block.macro(accountIdGen.genId("*accountId"))
                   .localTransform("$$emailToUser", Path.key("*email").multiPath(Path.key("accountId").termVal("*accountId"),
                                                                               Path.key("uuid").termVal("*uuid")))
                   .hashPartition("*accountId")
                   .localTransform("$$accountIdToAccount", Path.key("*accountId").termVal("*data"))
                   .each((RamaFunction2<Long, Account, AccountWithId>) AccountWithId::new, "*accountId", "*data").out("*accountWithId")
                   .depotPartitionAppend("*accountWithIdDepot", "*accountWithId"));
  }

  private void declareQueries(Topologies topologies) {
    topologies
      .query("getAccountsFromAccountIds", "*requestAccountId", "*accountIds")
      .out("*results")
      .each(Ops.EXPLODE_INDEXED, "*accountIds").out("*index", "*accountId")
      .select("$$accountIdToAccount", Path.key("*accountId")).out("*account")
      .each((RamaFunction2<Long, Account, AccountWithId>) AccountWithId::new,
            "*accountId", "*account").out("*accountWithId")
      .each((RamaFunction2<Integer, AccountWithId, IndexedAccountWithId>) 
            IndexedAccountWithId::new,
            "*index", "*accountWithId").out("*indexedAccountWithId")
      .originPartition()
      .agg(Agg.list("*indexedAccountWithId")).out("*unsortedResults")
      .each((List<IndexedAccountWithId> unsorted) -> {
          List<IndexedAccountWithId> sorted = new ArrayList<>(unsorted);
          sorted.sort(Comparator.comparingLong(o -> o.index));
          return sorted.stream()
                       .map(o -> o.accountWithId)
                       .collect(Collectors.toList());
      }, "*unsortedResults").out("*results");
}

    @Override
    public void define(Setup setup, Topologies topologies) {
      setup.declareDepot("*accountDepot", Depot.hashBy(CalypsoHelpers.ExtractEmail.class));
      setup.declareDepot("*accountWithIdDepot", Depot.disallow());

        declareAccountsTopology(topologies);
        declareQueries(topologies);
    }
    
}
