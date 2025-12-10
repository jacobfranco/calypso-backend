package now.calypso.backend;

import com.rpl.rama.*;
import com.rpl.rama.test.*;
import now.calypso.backend.data.*;
import now.calypso.backend.modules.*;
import now.calypso.backend.serialization.CalypsoSerialization;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class MatchesTest {

  private static final Map<String, double[]> CITY_LL = Map.of(
      "Charlotte, NC, USA", new double[] { 35.2271, -80.8431 },
      "Austin, TX, USA", new double[] { 30.2672, -97.7431 },
      "Denver, CO, USA", new double[] { 39.7392, -104.9903 },
      "Seattle, WA, USA", new double[] { 47.6062, -122.3321 },
      "Miami, FL, USA", new double[] { 25.7617, -80.1918 },
      "Sacramento, CA, USA", new double[] { 38.5816, -121.4944 },
      "San Francisco, CA, USA", new double[] { 37.7749, -122.4194 },
      "Boston, MA, USA", new double[] { 42.3601, -71.0589 });

  private static double radiusKmFromToken(String token) {
    if ("my_city".equals(token))
      return 35.0;
    if ("my_state".equals(token))
      return 250.0;
    if ("my_country".equals(token))
      return 3000.0;
    return 250.0;
  }

  private static Filters mkFilters(
      long accountId,
      double lat, double lon, double radiusKm,
      String modeSelf,
      String genderSelf, List<String> genderSeeking,
      int ageSelf, int ageMin, int ageMax,
      List<String> lifestyleSelf,
      List<TagPreference> lifestylePrefs,
      List<String> interestsSelf,
      List<TagPreference> interestsPrefs,
      OneToManyFilter religion,
      OneToManyFilter politics) {

    Filters f = new Filters();
    f.setAccountId(accountId);

    // relationship mode
    ModeFilter mode = new ModeFilter().setSelf(modeSelf);
    f.setRelationshipMode(mode);

    // gender
    OneToManyFilter g = new OneToManyFilter().setSelf(genderSelf);
    if (genderSeeking != null)
      g.setSeeking(genderSeeking);
    f.setGender(g);

    // age
    RangeFilter age = new RangeFilter().setSelf(ageSelf).setMin(ageMin).setMax(ageMax);
    f.setAge(age);

    // location (numeric only)
    LocationFilter loc = new LocationFilter()
        .setLat(lat)
        .setLon(lon)
        .setRadiusKm(radiusKm);
    f.setLocation(loc);

    if (religion != null)
      f.setReligion(religion);
    if (politics != null)
      f.setPolitics(politics);

    if (lifestyleSelf != null || lifestylePrefs != null) {
      ManyToManyFilter life = new ManyToManyFilter();
      if (lifestyleSelf != null)
        life.setSelf(lifestyleSelf);
      if (lifestylePrefs != null)
        life.setPreferences(lifestylePrefs);
      f.setLifestyle(life);
    }
    if (interestsSelf != null || interestsPrefs != null) {
      ManyToManyFilter ints = new ManyToManyFilter();
      if (interestsSelf != null)
        ints.setSelf(interestsSelf);
      if (interestsPrefs != null)
        ints.setPreferences(interestsPrefs);
      f.setInterests(ints);
    }
    return f;
  }

  private static TagPreference pref(String tag, Importance imp) {
    return new TagPreference().setTag(tag).setImportance(imp);
  }

  private static void append(InProcessCluster ipc, Depot d, Object o) {
    d.append(o);
  }

  private static void awaitPStateNonNull(PState ps, Path path) {
    TestHelpers.attainConditionPred(
        (com.rpl.rama.ops.RamaFunction0<Object>) () -> ps.selectOne(path),
        (com.rpl.rama.ops.RamaFunction1<Object, Boolean>) (v -> v != null));
  }

  private static void requestRefill(Depot refillDepot, long viewerId, int targetSize) {
    MatchRefillRequest r = new MatchRefillRequest()
        .setAccountId(viewerId)
        .setTargetSize(targetSize);
    refillDepot.append(r);
  }

  private static OneToManyFilter oneToMany(String self, List<String> seeking, Importance imp) {
    OneToManyFilter f = new OneToManyFilter();
    if (self != null)
      f.setSelf(self);
    if (seeking != null)
      f.setSeeking(seeking);
    if (imp != null)
      f.setImportance(imp);
    return f;
  }

  // ---------- Tests ----------

  @Test
  public void basicCompatibility_buildsHeap_and_QueryReturnsCandidate(TestInfo ti) throws Exception {
    List<Class> ser = List.of(CalypsoSerialization.class);
    try (InProcessCluster ipc = InProcessCluster.create(ser)) {
      Core core = new Core();
      Matches matches = new Matches();
      TestHelpers.launchModule(ipc, core, ti);
      TestHelpers.launchModule(ipc, matches, ti);

      String matchesName = matches.getClass().getName();

      Depot filtersDepot = ipc.clusterDepot(matchesName, "*filtersDepot");
      Depot refillDepot = ipc.clusterDepot(matchesName, "*matchRefillDepot");
      Depot serveDepot = ipc.clusterDepot(matchesName, "*matchesServeDepot");

      PState proj = ipc.clusterPState(matchesName, "$$accountIdToFiltersProjection");
      PState heapP = ipc.clusterPState(matchesName, "$$accountIdToCandidateHeap");

      QueryTopologyClient<List<MatchCandidate>> getMatchesQ = ipc.clusterQuery(matchesName, "getMatchesFromAccountId");

      // Two people near Charlotte; mutual gender/age; casual
      double[] CLT = CITY_LL.get("Charlotte, NC, USA");
      Filters viewer = mkFilters(
          1L, CLT[0], CLT[1], radiusKmFromToken("my_city"),
          "casual",
          "man", List.of("woman"),
          26, 22, 32,
          List.of("weightlifting"), List.of(pref("hiking", Importance.PREFERENCE)),
          null, null,
          null, null);
      Filters target = mkFilters(
          2L, CLT[0], CLT[1], radiusKmFromToken("my_city"),
          "casual",
          "woman", List.of("man"),
          25, 23, 35,
          List.of("hiking"), null,
          null, null,
          null, null);

      filtersDepot.append(viewer);
      filtersDepot.append(target);

      // Wait for projections
      awaitPStateNonNull(proj, Path.key(1L));
      awaitPStateNonNull(proj, Path.key(2L));

      // Refill viewer
      requestRefill(refillDepot, 1L, 50);

      // Wait until heap contains candidate 2
      TestHelpers.attainConditionPred(
          () -> (List<MatchCandidate>) heapP.selectOne(Path.key(1L)),
          heap -> heap != null && heap.stream().anyMatch(c -> c.getTargetAccountId() == 2L));

      // Query top 10 → should include 2 with reasonable score
      List<MatchCandidate> out = getMatchesQ.invoke(1L, 1L, 10);
      assertFalse(out.isEmpty(), "Expected at least one candidate");
      assertTrue(out.stream().anyMatch(c -> c.getTargetAccountId() == 2L));
      assertTrue(out.get(0).getStage0Score() >= 60.0, "Score should be >= base score");

      // Log exposure to exercise serve topology
      ServedPairs sp = new ServedPairs().setAccountId(1L)
          .setTargetIds(List.of(2L))
          .setServedAt(System.currentTimeMillis());
      serveDepot.append(sp);
    }
  }

  @Test
  public void genderIncompatibility_yieldsNoCandidates(TestInfo ti) throws Exception {
    List<Class> ser = List.of(CalypsoSerialization.class);
    try (InProcessCluster ipc = InProcessCluster.create(ser)) {
      Core core = new Core();
      Matches matches = new Matches();
      TestHelpers.launchModule(ipc, core, ti);
      TestHelpers.launchModule(ipc, matches, ti);

      String matchesName = matches.getClass().getName();

      Depot filtersDepot = ipc.clusterDepot(matchesName, "*filtersDepot");
      Depot refillDepot = ipc.clusterDepot(matchesName, "*matchRefillDepot");
      PState heapP = ipc.clusterPState(matchesName, "$$accountIdToCandidateHeap");

      double[] AUS = CITY_LL.get("Austin, TX, USA");
      Filters a = mkFilters(
          1L, AUS[0], AUS[1], radiusKmFromToken("my_city"),
          "casual",
          "man", List.of("woman"),
          30, 25, 35,
          null, null, null, null, null, null);
      Filters b = mkFilters(
          2L, AUS[0], AUS[1], radiusKmFromToken("my_city"),
          "casual",
          "man", List.of("woman"),
          29, 25, 35,
          null, null, null, null, null, null);

      append(ipc, filtersDepot, a);
      append(ipc, filtersDepot, b);

      requestRefill(refillDepot, 1L, 10);

      TestHelpers.attainConditionPred(
          () -> (List<MatchCandidate>) heapP.selectOne(Path.key(1L)),
          heap -> heap != null);

      List<MatchCandidate> heap = (List<MatchCandidate>) heapP.selectOne(Path.key(1L));
      assertTrue(heap.isEmpty(), "No candidates expected with incompatible genders");
    }
  }

  @Test
  public void exposureTTL_hidesRecentlyServed(TestInfo ti) throws Exception {
    List<Class> ser = List.of(CalypsoSerialization.class);
    try (InProcessCluster ipc = InProcessCluster.create(ser)) {
      Core core = new Core();
      Matches matches = new Matches();
      TestHelpers.launchModule(ipc, core, ti);
      TestHelpers.launchModule(ipc, matches, ti);

      String matchesName = matches.getClass().getName();

      Depot filtersDepot = ipc.clusterDepot(matchesName, "*filtersDepot");
      Depot refillDepot = ipc.clusterDepot(matchesName, "*matchRefillDepot");
      Depot serveDepot = ipc.clusterDepot(matchesName, "*matchesServeDepot");
      PState heapP = ipc.clusterPState(matchesName, "$$accountIdToCandidateHeap");

      // query client
      QueryTopologyClient<List<MatchCandidate>> getMatchesQ = ipc.clusterQuery(matchesName, "getMatchesFromAccountId");

      double[] SEA = CITY_LL.get("Seattle, WA, USA");
      // viewer + 2 compatible targets within radius
      append(ipc, filtersDepot, mkFilters(1L, SEA[0], SEA[1], radiusKmFromToken("my_city"),
          "casual", "woman", List.of("man"), 27, 22, 34, null, null, null, null, null, null));
      append(ipc, filtersDepot, mkFilters(2L, SEA[0], SEA[1], radiusKmFromToken("my_city"),
          "casual", "man", List.of("woman"), 28, 22, 34, null, null, null, null, null, null));
      append(ipc, filtersDepot, mkFilters(3L, SEA[0], SEA[1], radiusKmFromToken("my_city"),
          "casual", "man", List.of("woman"), 29, 22, 34, null, null, null, null, null, null));

      // initial refill
      requestRefill(refillDepot, 1L, 10);
      TestHelpers.attainConditionPred(
          () -> (List<MatchCandidate>) heapP.selectOne(Path.key(1L)),
          heap -> heap != null && heap.size() >= 2);

      // Baseline: both 2 and 3 should be in the query results
      List<MatchCandidate> before = getMatchesQ.invoke(1L, 1L, 10);
      assertTrue(before.stream().anyMatch(c -> c.getTargetAccountId() == 2L));
      assertTrue(before.stream().anyMatch(c -> c.getTargetAccountId() == 3L));

      // serve (expose) id 2
      ServedPairs sp = new ServedPairs().setAccountId(1L)
          .setTargetIds(List.of(2L))
          .setServedAt(System.currentTimeMillis());
      append(ipc, serveDepot, sp);

      // optionally, we can refill or not; TTL is enforced at query time now.
      requestRefill(refillDepot, 1L, 10);
      TestHelpers.attainConditionPred(
          () -> (List<MatchCandidate>) heapP.selectOne(Path.key(1L)),
          heap -> heap != null && !heap.isEmpty());

      // Query again: id 2 should be hidden by exposure TTL
      List<MatchCandidate> after = getMatchesQ.invoke(1L, 1L, 10);
      assertFalse(after.stream().anyMatch(c -> c.getTargetAccountId() == 2L),
          "Recently served id=2 should be hidden by exposure TTL");
      assertTrue(after.stream().anyMatch(c -> c.getTargetAccountId() == 3L),
          "Unserved id=3 should still be visible");
    }
  }

  @Test
  public void seriousMode_enforcesHigherScoreFloor(TestInfo ti) throws Exception {
    List<Class> ser = List.of(CalypsoSerialization.class);
    try (InProcessCluster ipc = InProcessCluster.create(ser)) {
      Core core = new Core();
      Matches matches = new Matches();
      TestHelpers.launchModule(ipc, core, ti);
      TestHelpers.launchModule(ipc, matches, ti);

      String matchesName = matches.getClass().getName();

      Depot filtersDepot = ipc.clusterDepot(matchesName, "*filtersDepot");
      Depot refillDepot = ipc.clusterDepot(matchesName, "*matchRefillDepot");
      PState heapP = ipc.clusterPState(matchesName, "$$accountIdToCandidateHeap");

      double[] MIA = CITY_LL.get("Miami, FL, USA");
      // serious vs casual candidates → serious floor should exclude some
      append(ipc, filtersDepot, mkFilters(1L, MIA[0], MIA[1], radiusKmFromToken("my_city"),
          "serious", "woman", List.of("man"), 29, 24, 36, null, null, null, null, null, null));
      append(ipc, filtersDepot, mkFilters(2L, MIA[0], MIA[1], radiusKmFromToken("my_city"),
          "casual", "man", List.of("woman"), 30, 24, 36, null, null, null, null, null, null));
      append(ipc, filtersDepot, mkFilters(3L, MIA[0], MIA[1], radiusKmFromToken("my_city"),
          "serious", "man", List.of("woman"), 31, 24, 36, null, null, null, null, null, null));

      requestRefill(refillDepot, 1L, 10);
      TestHelpers.attainConditionPred(
          () -> (List<MatchCandidate>) heapP.selectOne(Path.key(1L)),
          heap -> heap != null);

      List<MatchCandidate> heap = (List<MatchCandidate>) heapP.selectOne(Path.key(1L));
      assertTrue(heap.stream().anyMatch(c -> c.getTargetAccountId() == 3L),
          "Serious match should survive higher floor");
      assertTrue(heap.stream().noneMatch(c -> c.getTargetAccountId() == 2L),
          "Casual candidate should be cut by serious floor");
    }
  }

  @Test
  public void radiusPools_cityVsState(TestInfo ti) throws Exception {
    List<Class> ser = List.of(CalypsoSerialization.class);
    try (InProcessCluster ipc = InProcessCluster.create(ser)) {
      Core core = new Core();
      Matches matches = new Matches();
      TestHelpers.launchModule(ipc, core, ti);
      TestHelpers.launchModule(ipc, matches, ti);

      String matchesName = matches.getClass().getName();

      Depot filtersDepot = ipc.clusterDepot(matchesName, "*filtersDepot");
      Depot refillDepot = ipc.clusterDepot(matchesName, "*matchRefillDepot");
      PState heapP = ipc.clusterPState(matchesName, "$$accountIdToCandidateHeap");

      double[] SAC = CITY_LL.get("Sacramento, CA, USA");
      double[] SFO = CITY_LL.get("San Francisco, CA, USA");

      // 1) Viewer radius ~35km: SF (~121km) should be excluded
      filtersDepot.append(mkFilters(1L, SAC[0], SAC[1], radiusKmFromToken("my_city"),
          "casual", "woman", List.of("man"), 28, 24, 36, null, null, null, null, null, null));
      // Targets: one colocated in Sacramento; one in SF
      filtersDepot.append(mkFilters(2L, SAC[0], SAC[1], radiusKmFromToken("my_state"),
          "casual", "man", List.of("woman"), 29, 24, 36, null, null, null, null, null, null));
      filtersDepot.append(mkFilters(3L, SFO[0], SFO[1], radiusKmFromToken("my_state"),
          "casual", "man", List.of("woman"), 29, 24, 36, null, null, null, null, null, null));

      requestRefill(refillDepot, 1L, 50);
      TestHelpers.attainConditionPred(
          () -> (List<MatchCandidate>) heapP.selectOne(Path.key(1L)),
          heap -> heap != null);

      List<MatchCandidate> heap1 = (List<MatchCandidate>) heapP.selectOne(Path.key(1L));
      Set<Long> ids1 = new HashSet<>();
      for (MatchCandidate c : heap1)
        ids1.add(c.getTargetAccountId());
      assertTrue(ids1.contains(2L), "Nearby city peer should be included at city radius");
      assertFalse(ids1.contains(3L), "Farther city should be excluded at city radius");

      // 2) Increase viewer radius to ~250km: SF should be included now
      filtersDepot.append(mkFilters(1L, SAC[0], SAC[1], radiusKmFromToken("my_state"),
          "casual", "woman", List.of("man"), 28, 24, 36, null, null, null, null, null, null));

      requestRefill(refillDepot, 1L, 50);
      TestHelpers.attainConditionPred(
          () -> (List<MatchCandidate>) heapP.selectOne(Path.key(1L)),
          heap -> heap != null);

      List<MatchCandidate> heap2 = (List<MatchCandidate>) heapP.selectOne(Path.key(1L));
      Set<Long> ids2 = new HashSet<>();
      for (MatchCandidate c : heap2)
        ids2.add(c.getTargetAccountId());
      assertTrue(ids2.contains(2L), "Nearby city peer should remain included");
      assertTrue(ids2.contains(3L), "Farther city should be included at larger radius");
    }
  }

  @Test
  public void cursorAdvances_andWrapsOnce(TestInfo ti) throws Exception {
    List<Class> ser = List.of(CalypsoSerialization.class);
    try (InProcessCluster ipc = InProcessCluster.create(ser)) {
      Core core = new Core();
      Matches matches = new Matches();
      TestHelpers.launchModule(ipc, core, ti);
      TestHelpers.launchModule(ipc, matches, ti);

      String matchesName = matches.getClass().getName();

      Depot filtersDepot = ipc.clusterDepot(matchesName, "*filtersDepot");
      Depot refillDepot = ipc.clusterDepot(matchesName, "*matchRefillDepot");
      Depot ackDepot = ipc.clusterDepot(matchesName, "*matchesCursorAckDepot");

      // query that returns page + next cursor
      QueryTopologyClient<Map<String, Object>> getMatchesQ = ipc.clusterQuery(matchesName,
          "getMatchesFromAccountIdWithCursor");

      double[] BOS = CITY_LL.get("Boston, MA, USA");
      // viewer + 3 candidates compatible
      append(ipc, filtersDepot, mkFilters(1L, BOS[0], BOS[1], radiusKmFromToken("my_city"),
          "casual", "woman", List.of("man"), 27, 22, 34, null, null, null, null, null, null));
      append(ipc, filtersDepot, mkFilters(2L, BOS[0], BOS[1], radiusKmFromToken("my_city"),
          "casual", "man", List.of("woman"), 28, 22, 34, null, null, null, null, null, null));
      append(ipc, filtersDepot, mkFilters(3L, BOS[0], BOS[1], radiusKmFromToken("my_city"),
          "casual", "man", List.of("woman"), 29, 22, 34, null, null, null, null, null, null));
      append(ipc, filtersDepot, mkFilters(4L, BOS[0], BOS[1], radiusKmFromToken("my_city"),
          "casual", "man", List.of("woman"), 30, 22, 34, null, null, null, null, null, null));

      requestRefill(refillDepot, 1L, 2);

      Map<String, Object> res1 = getMatchesQ.invoke(1L, 1L, 2);
      @SuppressWarnings("unchecked")
      List<MatchCandidate> page1 = (List<MatchCandidate>) res1.get("page");
      assertEquals(2, page1.size(), "First page should have 2 items");

      // ACK cursor 1
      int nextIdx1 = (Integer) res1.get("nextIdx");
      boolean nextWrapped1 = (Boolean) res1.get("nextWrapped");
      CursorAck ack1 = new CursorAck().setAccountId(1L).setLastIndex(nextIdx1).setWrappedOnce(nextWrapped1);
      append(ipc, ackDepot, ack1);

      Map<String, Object> res2 = getMatchesQ.invoke(1L, 1L, 2);
      @SuppressWarnings("unchecked")
      List<MatchCandidate> page2 = (List<MatchCandidate>) res2.get("page");
      assertEquals(2, page2.size(), "Second page should have 2 items");

      // ACK cursor 2
      int nextIdx2 = (Integer) res2.get("nextIdx");
      boolean nextWrapped2 = (Boolean) res2.get("nextWrapped");
      CursorAck ack2 = new CursorAck().setAccountId(1L).setLastIndex(nextIdx2).setWrappedOnce(nextWrapped2);
      append(ipc, ackDepot, ack2);

      Map<String, Object> res3 = getMatchesQ.invoke(1L, 1L, 2);
      @SuppressWarnings("unchecked")
      List<MatchCandidate> page3 = (List<MatchCandidate>) res3.get("page");
      assertTrue(page3.isEmpty(), "Cursor should wrap only after serving all and be empty on 3rd call");
    }
  }

  @Test
  public void politicsDealbreaker_blocksIncompatibleCandidates(TestInfo ti) throws Exception {
    List<Class> ser = List.of(CalypsoSerialization.class);
    try (InProcessCluster ipc = InProcessCluster.create(ser)) {
      Core core = new Core();
      Matches matches = new Matches();
      TestHelpers.launchModule(ipc, core, ti);
      TestHelpers.launchModule(ipc, matches, ti);

      String matchesName = matches.getClass().getName();

      Depot filtersDepot = ipc.clusterDepot(matchesName, "*filtersDepot");
      Depot refillDepot = ipc.clusterDepot(matchesName, "*matchRefillDepot");
      PState heapP = ipc.clusterPState(matchesName, "$$accountIdToCandidateHeap");

      double[] BOS = CITY_LL.get("Boston, MA, USA");

      // Viewer: politics DEALBREAKER, only wants "left" or "center_left"
      Filters viewer = mkFilters(
          1L, BOS[0], BOS[1], radiusKmFromToken("my_city"),
          "casual",
          "woman", List.of("man"),
          27, 22, 34,
          null, null, null, null,
          null,
          oneToMany("left", List.of("left", "center_left"), Importance.DEALBREAKER));

      // Target 2: compatible politics ("left")
      Filters targetOk = mkFilters(
          2L, BOS[0], BOS[1], radiusKmFromToken("my_city"),
          "casual",
          "man", List.of("woman"),
          28, 22, 34,
          null, null, null, null,
          null,
          oneToMany("left", null, Importance.NOT_IMPORTANT));

      // Target 3: incompatible politics ("right")
      Filters targetBad = mkFilters(
          3L, BOS[0], BOS[1], radiusKmFromToken("my_city"),
          "casual",
          "man", List.of("woman"),
          29, 22, 34,
          null, null, null, null,
          null,
          oneToMany("right", null, Importance.NOT_IMPORTANT));

      append(ipc, filtersDepot, viewer);
      append(ipc, filtersDepot, targetOk);
      append(ipc, filtersDepot, targetBad);

      requestRefill(refillDepot, 1L, 10);

      TestHelpers.attainConditionPred(
          () -> (List<MatchCandidate>) heapP.selectOne(Path.key(1L)),
          heap -> heap != null);

      List<MatchCandidate> heap = (List<MatchCandidate>) heapP.selectOne(Path.key(1L));
      Set<Long> ids = new HashSet<>();
      for (MatchCandidate c : heap)
        ids.add(c.getTargetAccountId());

      assertTrue(ids.contains(2L), "Politically compatible target should be included");
      assertFalse(ids.contains(3L), "Politically incompatible target should be excluded by dealbreaker");
    }
  }

  @Test
  public void politicsPreference_boostsScoreForPreferredTags(TestInfo ti) throws Exception {
    List<Class> ser = List.of(CalypsoSerialization.class);
    try (InProcessCluster ipc = InProcessCluster.create(ser)) {
      Core core = new Core();
      Matches matches = new Matches();
      TestHelpers.launchModule(ipc, core, ti);
      TestHelpers.launchModule(ipc, matches, ti);

      String matchesName = matches.getClass().getName();

      Depot filtersDepot = ipc.clusterDepot(matchesName, "*filtersDepot");
      Depot refillDepot = ipc.clusterDepot(matchesName, "*matchRefillDepot");
      PState heapP = ipc.clusterPState(matchesName, "$$accountIdToCandidateHeap");

      double[] DEN = CITY_LL.get("Denver, CO, USA");

      // Viewer: politically "center", prefers "left"
      Filters viewer = mkFilters(
          1L, DEN[0], DEN[1], radiusKmFromToken("my_city"),
          "casual",
          "woman", List.of("man"),
          27, 22, 34,
          null, null, null, null,
          null,
          oneToMany("center", List.of("left"), Importance.PREFERENCE));

      // Target 2: "left" -> should get politics bonus
      Filters targetPreferred = mkFilters(
          2L, DEN[0], DEN[1], radiusKmFromToken("my_city"),
          "casual",
          "man", List.of("woman"),
          28, 22, 34,
          null, null, null, null,
          null,
          oneToMany("left", null, Importance.NOT_IMPORTANT));

      // Target 3: "center" -> no politics bonus
      Filters targetNeutral = mkFilters(
          3L, DEN[0], DEN[1], radiusKmFromToken("my_city"),
          "casual",
          "man", List.of("woman"),
          29, 22, 34,
          null, null, null, null,
          null,
          oneToMany("center", null, Importance.NOT_IMPORTANT));

      append(ipc, filtersDepot, viewer);
      append(ipc, filtersDepot, targetPreferred);
      append(ipc, filtersDepot, targetNeutral);

      requestRefill(refillDepot, 1L, 10);

      TestHelpers.attainConditionPred(
          () -> (List<MatchCandidate>) heapP.selectOne(Path.key(1L)),
          heap -> heap != null && heap.size() >= 2);

      List<MatchCandidate> heap = (List<MatchCandidate>) heapP.selectOne(Path.key(1L));

      MatchCandidate preferred = heap.stream()
          .filter(c -> c.getTargetAccountId() == 2L)
          .findFirst()
          .orElseThrow(() -> new AssertionError("Preferred politics candidate not found"));

      MatchCandidate neutral = heap.stream()
          .filter(c -> c.getTargetAccountId() == 3L)
          .findFirst()
          .orElseThrow(() -> new AssertionError("Neutral politics candidate not found"));

      assertTrue(preferred.getStage0Score() > neutral.getStage0Score(),
          "Candidate matching viewer's politics preference should have higher score");
    }
  }

}
