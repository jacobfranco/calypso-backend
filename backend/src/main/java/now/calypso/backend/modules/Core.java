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

      private static void declareApplicationTopology(Topologies topologies) {
                StreamTopology stream = topologies.stream("applications");
                // Declare a PState to map client IDs to Application objects
                stream.pstate("$$clientIdToApplication", PState.mapSchema(String.class, Application.class));
                // Source from the application depot
                stream.source("*applicationDepot").out("*application")
                                .localTransform("$$clientIdToApplication",
                                                Path.key(new Expr(Application::getClient_id, "*application"))
                                                                .termVal("*application"));
        }

      private static void declarePromptsTopology(Topologies topologies) {
            StreamTopology stream = topologies.stream("prompts");

            stream.pstate("$$accountIdToPrompts", PState.mapSchema(Long.class, PromptState.class));

            stream.source("*promptsDepot")
                        .out("*data")
                        .macro(extractFields("*data", "*accountId"))
                        .localTransform("$$accountIdToPrompts",
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

            topologies.query("getApplicationFromClientId", "*client_id").out("*result")
                                .hashPartition("*client_id")
                                .localSelect("$$clientIdToApplication", Path.key("*client_id"))
                                .out("*application")
                                .ifTrue(new Expr(Ops.IS_NULL, "*application"),
                                                Block.each(() -> null).out("*result"),
                                                Block.each(Ops.IDENTITY, "*application").out("*result"))
                                .originPartition();

            topologies.query("getPromptsStateFromAccountId", "*requestAccountId", "*accountId").out("*prompts")
                        .hashPartition("*accountId")
                        .localSelect("$$accountIdToPrompts", Path.key("*accountId")).out("*prompts")
                        .originPartition();
      }

      @Override
      public void define(Setup setup, Topologies topologies) {
            setup.declareDepot("*accountDepot", Depot.hashBy(CalypsoHelpers.ExtractEmail.class));
            setup.declareDepot("*accountWithIdDepot", Depot.disallow());
            setup.declareDepot("*applicationDepot", Depot.hashBy(CalypsoHelpers.ExtractClientId.class));
            setup.declareDepot("*authCodeDepot", Depot.hashBy(ExtractCode.class));
            setup.declareDepot("*promptsDepot", Depot.hashBy(CalypsoHelpers.ExtractAccountId.class));

            declareAccountsTopology(topologies);
            declareApplicationTopology(topologies);
            declareAuthTopology(topologies);
            declarePromptsTopology(topologies);

            declareQueries(topologies);
      }

}
