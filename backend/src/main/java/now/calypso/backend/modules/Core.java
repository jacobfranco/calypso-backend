package now.calypso.backend.modules;

import org.apache.thrift.protocol.TField;

import com.rpl.rama.*;
import com.rpl.rama.module.*;
import com.rpl.rama.ops.*;
import com.rpl.rama.helpers.*;

import now.calypso.backend.*;
import now.calypso.backend.CalypsoHelpers.ExtractCode;
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
                        // Accept either first write or an idempotent retry from the same UUID
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

      private void declareAuthTopology(Topologies topologies) {
            StreamTopology stream = topologies.stream("relationshipsStream");

            stream.pstate(
                        "$$authCodeToAccountId",
                        PState.mapSchema(String.class, Long.class));

            stream.source("*authCodeDepot").out("*data")
                        .subSource("*data",
                                    SubSource.create(AddAuthCode.class)
                                                .macro(extractFields("*data", "*code", "*accountId"))
                                                .localTransform(
                                                            "$$authCodeToAccountId",
                                                            Path.key("*code").termVal("*accountId")),
                                    SubSource.create(RemoveAuthCode.class)
                                                .macro(extractFields("*data", "*code"))
                                                .localTransform(
                                                            "$$authCodeToAccountId",
                                                            Path.key("*code").termVoid()));
      }

      private static void declareSignalsTopology(Topologies topologies) {
            StreamTopology stream = topologies.stream("signals");

            stream.pstate("$$accountIdToSignals", PState.mapSchema(Long.class, Signals.class));

            stream.source("*signalsDepot")
                        .out("*data")
                        .macro(extractFields("*data", "*accountId"))
                        .localTransform("$$accountIdToSignals",
                                    Path.key("*accountId").termVal("*data"));
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

            topologies.query("getSignalsFromAccountId", "*requestAccountId", "*accountId").out("*signals")
                        .hashPartition("*accountId")
                        .localSelect("$$accountIdToSignals", Path.key("*accountId")).out("*signals")
                        .originPartition();
      }

      @Override
      public void define(Setup setup, Topologies topologies) {
            setup.declareDepot("*accountDepot", Depot.hashBy(CalypsoHelpers.ExtractEmail.class));
            setup.declareDepot("*accountWithIdDepot", Depot.disallow());
            setup.declareDepot("*authCodeDepot", Depot.hashBy(ExtractCode.class));
            setup.declareDepot("*signalsDepot", Depot.hashBy(CalypsoHelpers.ExtractAccountId.class));

            declareAccountsTopology(topologies);
            declareAuthTopology(topologies);
            declareSignalsTopology(topologies);

            declareQueries(topologies);
      }

}
