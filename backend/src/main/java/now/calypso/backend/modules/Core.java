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

      private static void declareAccountsTopology(Topologies topologies) {
            StreamTopology stream = topologies.stream("accounts");
            ModuleUniqueIdPState accountIdGen = new ModuleUniqueIdPState("$$accountIdGen");
            accountIdGen.declarePState(stream);
            stream.pstate("$$emailToUser", PState.mapSchema(String.class,
                        PState.fixedKeysSchema("accountId", Long.class,
                                    "uuid", String.class)));
            stream.pstate("$$accountIdToAccount", PState.mapSchema(Long.class, Account.class));

            stream.source("*accountDepot").out("*data")
                        .macro(extractFields("*data", "*email", "*uuid"))
                        .localSelect("$$emailToUser", Path.key("*email")).out("*currInfo")
                        .each(Ops.GET, "*currInfo", "uuid").out("*currUUID")
                        // By including a UUID with each registration request, we can distinguish
                        // between:
                        // - this email is already registered by a different request so we shouldn't
                        // override it
                        // - this email was registered by the same request, so we should continue
                        // finishing the
                        // registration
                        .ifTrue(new Expr(Ops.OR, new Expr(Ops.IS_NULL, "*currInfo"),
                                    new Expr(Ops.EQUAL, "*uuid", "*currUUID")),
                                    Block.macro(accountIdGen.genId("*accountId"))
                                                .localTransform("$$emailToUser",
                                                            Path.key("*email").multiPath(
                                                                        Path.key("accountId").termVal("*accountId"),
                                                                        Path.key("uuid").termVal("*uuid")))
                                                .hashPartition("*accountId")
                                                .localTransform("$$accountIdToAccount",
                                                            Path.key("*accountId").termVal("*data"))
                                                .each((RamaFunction2<Long, Account, AccountWithId>) AccountWithId::new,
                                                            "*accountId", "*data")
                                                .out("*accountWithId")
                                                .depotPartitionAppend("*accountWithIdDepot", "*accountWithId"));
      }

      private static void declareFiltersTopology(Topologies topologies) {
            StreamTopology stream = topologies.stream("filters");

            stream.pstate("$$accountIdToFilters",
                        PState.mapSchema(Long.class, Filters.class));

            stream.source("*filtersDepot")
                        .out("*data")
                        .macro(extractFields("*data", "*accountId"))
                        .localTransform("$$accountIdToFilters",
                                    Path.key("*accountId")
                                                .termVal("*data"));
      }

      private void declareQueries(Topologies topologies) {
            topologies
                        .query("getAccountsFromAccountIds", "*requestAccountId", "*accountIds")
                        .out("*results")
                        .each(Ops.EXPLODE_INDEXED, "*accountIds").out("*index", "*accountId")
                        .select("$$accountIdToAccount", Path.key("*accountId")).out("*account")
                        .each((RamaFunction2<Long, Account, AccountWithId>) AccountWithId::new,
                                    "*accountId", "*account")
                        .out("*accountWithId")
                        .each((RamaFunction2<Integer, AccountWithId, IndexedAccountWithId>) IndexedAccountWithId::new,
                                    "*index", "*accountWithId")
                        .out("*indexedAccountWithId")
                        .originPartition()
                        .agg(Agg.list("*indexedAccountWithId")).out("*unsortedResults")
                        .each((List<IndexedAccountWithId> unsorted) -> {
                              List<IndexedAccountWithId> sorted = new ArrayList<>(unsorted);
                              sorted.sort(Comparator.comparingLong(o -> o.index));
                              return sorted.stream()
                                          .map(o -> o.accountWithId)
                                          .collect(Collectors.toList());
                        }, "*unsortedResults").out("*results");

            topologies.query("getFiltersFromAccountId", "*requestAccountId", "*accountId").out("*filters")
                        .hashPartition("*accountId")
                        .localSelect("$$accountIdToFilters", Path.key("*accountId")).out("*filters")
                        .originPartition();

      }

      @Override
      public void define(Setup setup, Topologies topologies) {
            setup.declareDepot("*accountDepot", Depot.hashBy(CalypsoHelpers.ExtractEmail.class));
            setup.declareDepot("*accountWithIdDepot", Depot.disallow());
            setup.declareDepot("*filtersDepot",
                        Depot.hashBy(CalypsoHelpers.ExtractAccountId.class));

            declareAccountsTopology(topologies);
            declareFiltersTopology(topologies);

            declareQueries(topologies);
      }

}
