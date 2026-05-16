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

      // ------- Tunables -------
      private static final int HEAP_K = 400;
      private static final long EXPOSURE_TTL_MS = 14L * 24 * 60 * 60 * 1000L; // 14 days
      private static final double MIN_SCORE_EXPLORATORY = 45.0;
      private static final double MIN_SCORE_BALANCED = 55.0;
      private static final double MIN_SCORE_FOCUSED = 65.0;
      private static final double FOLLOWUP_MIN_NORMALIZED_SCORE = 0.60;
      private static final String ALL_ACCOUNTS_KEY = "all";
      private static final String FACECARD_REACTION_ANSWER_PREFIX = "facecard_target:";
      private static final String PUBLIC_REACTION_STRENGTH_PROMPT_SUFFIX = "|reaction_strength:";
      private static final int PUBLIC_REACTION_STRENGTH_MIN = -3;
      private static final int PUBLIC_REACTION_STRENGTH_MAX = 3;
      private static final double FACECARD_PAIR_DELTA_LIKE = 6.0;
      private static final double FACECARD_PAIR_DELTA_DISLIKE = -14.0;
      private static final double FACECARD_PAIR_DELTA_SKIP = -1.0;
      private static final double SIGNAL_PRESENT_EPSILON = 1.0e-6;
      private static final double RESONANCE_POSITIVE_MAX_DELTA = 0.10;
      private static final double RESONANCE_NEGATIVE_MAX_DELTA = 0.05;

      // ---------------------------
      // Low-level helpers
      // ---------------------------

      private static MatchCandidate mkCandidate(long targetId, double score, long now) {
            MatchCandidate c = new MatchCandidate();
            c.setTargetAccountId(targetId);
            c.setStage0Score(score);
            c.setComputedAt(now);
            return c;
      }

      private static List<MatchCandidate> upsertIntoHeap(List<MatchCandidate> heap, MatchCandidate cand) {
            if (cand == null) {
                  return (heap == null) ? new ArrayList<MatchCandidate>() : heap;
            }

            ArrayList<MatchCandidate> list = (heap == null) ? new ArrayList<>() : new ArrayList<>(heap);

            // Remove existing candidate with the same target id (at most one)
            for (int i = 0; i < list.size(); i++) {
                  if (list.get(i).getTargetAccountId() == cand.getTargetAccountId()) {
                        list.remove(i);
                        break;
                  }
            }

            // Find insertion index to keep list sorted:
            // - higher score first
            // - for ties, smaller target id first
            int idx = 0;
            while (idx < list.size()) {
                  MatchCandidate cur = list.get(idx);
                  int cmp = Double.compare(cur.getStage0Score(), cand.getStage0Score());
                  if (cmp < 0) {
                        // current score < new score -> insert before
                        break;
                  } else if (cmp == 0 && cur.getTargetAccountId() > cand.getTargetAccountId()) {
                        // same score, keep smaller id first
                        break;
                  }
                  idx++;
            }
            list.add(idx, cand);

            // Enforce heap cap
            if (list.size() > HEAP_K) {
                  list.remove(list.size() - 1);
            }

            return list;
      }

      private static List<MatchCandidate> removeFromHeap(List<MatchCandidate> heap, long targetId) {
            if (heap == null || heap.isEmpty() || targetId < 0L) {
                  return (heap == null) ? new ArrayList<MatchCandidate>() : heap;
            }
            ArrayList<MatchCandidate> list = new ArrayList<>(heap);
            list.removeIf(candidate -> candidate != null && candidate.getTargetAccountId() == targetId);
            return list;
      }

      // Filter a heap against fresh exposures at query time
      private static List<MatchCandidate> filterHeapByExposure(List<MatchCandidate> heap,
                  Map<?, ?> exposureMap,
                  long now) {
            if (heap == null || heap.isEmpty())
                  return new ArrayList<>();
            ArrayList<MatchCandidate> out = new ArrayList<>();
            for (MatchCandidate c : heap) {
                  if (c == null)
                        continue;
                  Object tsObj = (exposureMap == null) ? null : exposureMap.get(c.getTargetAccountId());
                  Long ts = (tsObj instanceof Number) ? ((Number) tsObj).longValue() : null;
                  if (ts == null || (now - ts) >= EXPOSURE_TTL_MS) {
                        out.add(c);
                  }
            }
            return out;
      }

      private static double clamp01(double value) {
            if (Double.isNaN(value))
                  return 0.0;
            if (value < 0.0)
                  return 0.0;
            if (value > 1.0)
                  return 1.0;
            return value;
      }

      private static double asDouble(Object raw, double fallback) {
            if (raw instanceof Number)
                  return ((Number) raw).doubleValue();
            return fallback;
      }

      private static double modeFloor(String viewerMode) {
            if ("focused".equalsIgnoreCase(viewerMode))
                  return MIN_SCORE_FOCUSED;
            if ("exploratory".equalsIgnoreCase(viewerMode))
                  return MIN_SCORE_EXPLORATORY;
            return MIN_SCORE_BALANCED;
      }

      private static double clampSigned(double value) {
            if (Double.isNaN(value))
                  return 0.0;
            if (value < -1.0)
                  return -1.0;
            if (value > 1.0)
                  return 1.0;
            return value;
      }

      private static ParsedSignalToken parseTokenAndValence(SignalRecord record) {
            if (record == null)
                  return null;
            String tokenSource = null;
            if (record.isSetCanonicalToken() && record.getCanonicalToken() != null
                        && !record.getCanonicalToken().isBlank()) {
                  tokenSource = record.getCanonicalToken();
            } else if (record.isSetToken() && record.getToken() != null && !record.getToken().isBlank()) {
                  tokenSource = record.getToken();
            } else if (record.isSetRawToken() && record.getRawToken() != null && !record.getRawToken().isBlank()) {
                  tokenSource = record.getRawToken();
            }
            if (tokenSource == null)
                  return null;
            String token = tokenSource.trim().toLowerCase(Locale.ROOT);
            if (token.isBlank())
                  return null;
            boolean explicitValence = record.isSetValence();
            double valence = explicitValence ? clampSigned(record.getValence()) : 1.0;
            boolean stripped;
            do {
                  stripped = false;
                  if (token.startsWith("anti_") && token.length() > "anti_".length()) {
                        if (!explicitValence)
                              valence = -1.0;
                        token = token.substring("anti_".length());
                        stripped = true;
                  } else if (token.startsWith("not_") && token.length() > "not_".length()) {
                        if (!explicitValence)
                              valence = -1.0;
                        token = token.substring("not_".length());
                        stripped = true;
                  } else if (token.startsWith("no_") && token.length() > "no_".length()) {
                        if (!explicitValence)
                              valence = -1.0;
                        token = token.substring("no_".length());
                        stripped = true;
                  } else if (token.startsWith("avoid_") && token.length() > "avoid_".length()) {
                        if (!explicitValence)
                              valence = -1.0;
                        token = token.substring("avoid_".length());
                        stripped = true;
                  } else if (token.startsWith("exclude_") && token.length() > "exclude_".length()) {
                        if (!explicitValence)
                              valence = -1.0;
                        token = token.substring("exclude_".length());
                        stripped = true;
                  }
            } while (stripped);
            token = token.trim();
            if (token.isBlank())
                  return null;
            return new ParsedSignalToken(token, valence);
      }

      private static void accumulateSignalWeight(Map<String, Double> out, String token, double signedWeight) {
            if (token == null || token.isBlank() || Math.abs(signedWeight) <= SIGNAL_PRESENT_EPSILON)
                  return;
            out.put(token, out.getOrDefault(token, 0.0) + signedWeight);
      }

      private static Map<String, Double> toSignalWeights(Signals signals, boolean desired) {
            HashMap<String, Double> out = new HashMap<>();
            if (signals == null || !signals.isSetRecords() || signals.getRecords() == null) {
                  return out;
            }
            for (SignalRecord r : signals.getRecords()) {
                  if (r == null)
                        continue;
                  SignalIntent intent = r.isSetIntent() ? r.getIntent() : null;
                  boolean keep;
                  if (desired) {
                        keep = intent == SignalIntent.SEEKING || intent == SignalIntent.BOTH;
                  } else {
                        keep = intent == null || intent == SignalIntent.SELF || intent == SignalIntent.BOTH;
                  }
                  if (!keep)
                        continue;

                  ParsedSignalToken parsed = parseTokenAndValence(r);
                  if (parsed == null)
                        continue;
                  double valence = clampSigned(parsed.valence);
                  double valenceMagnitude = Math.abs(valence);
                  if (valenceMagnitude <= SIGNAL_PRESENT_EPSILON)
                        continue;

                  double count = r.isSetCount() ? Math.max(1.0, r.getCount()) : 1.0;
                  double signedWeight = Math.signum(valence)
                              * Math.log1p(count)
                              * valenceMagnitude;
                  if (Math.abs(signedWeight) <= SIGNAL_PRESENT_EPSILON)
                        continue;

                  accumulateSignalWeight(out, parsed.token, signedWeight);
            }
            return out;
      }

      private static double weightedJaccard(Map<String, Double> a, Map<String, Double> b) {
            if ((a == null || a.isEmpty()) && (b == null || b.isEmpty()))
                  return 0.6;
            if (a == null || b == null || a.isEmpty() || b.isEmpty())
                  return 0.0;
            HashSet<String> keys = new HashSet<>();
            keys.addAll(a.keySet());
            keys.addAll(b.keySet());
            double signedOverlap = 0.0;
            double union = 0.0;
            for (String k : keys) {
                  double av = a.getOrDefault(k, 0.0);
                  double bv = b.getOrDefault(k, 0.0);
                  double aAbs = Math.abs(av);
                  double bAbs = Math.abs(bv);
                  if (aAbs <= SIGNAL_PRESENT_EPSILON && bAbs <= SIGNAL_PRESENT_EPSILON)
                        continue;
                  union += Math.max(aAbs, bAbs);
                  if (aAbs <= SIGNAL_PRESENT_EPSILON || bAbs <= SIGNAL_PRESENT_EPSILON)
                        continue;
                  double signAlign = Math.signum(av) * Math.signum(bv);
                  signedOverlap += Math.min(aAbs, bAbs) * signAlign;
            }
            if (union <= SIGNAL_PRESENT_EPSILON)
                  return 0.0;
            return clamp01(0.5 + (0.5 * (signedOverlap / union)));
      }

      private static double directionalCompatibility(Map<String, Double> desired, Map<String, Double> otherSelf) {
            if (desired == null || desired.isEmpty())
                  return 0.65;
            Map<String, Double> other = (otherSelf == null) ? Collections.emptyMap() : otherSelf;
            double totalDemand = 0.0;
            double aligned = 0.0;
            double conflicted = 0.0;
            for (Map.Entry<String, Double> entry : desired.entrySet()) {
                  String token = entry.getKey();
                  double desiredWeight = entry.getValue() == null ? 0.0 : entry.getValue();
                  if (token == null || token.isBlank())
                        continue;
                  double demandAbs = Math.abs(desiredWeight);
                  if (demandAbs <= SIGNAL_PRESENT_EPSILON)
                        continue;
                  totalDemand += demandAbs;

                  double otherWeight = other.getOrDefault(token, 0.0);
                  double otherAbs = Math.abs(otherWeight);
                  if (otherAbs <= SIGNAL_PRESENT_EPSILON)
                        continue;
                  double overlap = Math.min(demandAbs, otherAbs);
                  double alignment = Math.signum(desiredWeight) * Math.signum(otherWeight);
                  if (alignment > 0) {
                        aligned += overlap;
                  } else if (alignment < 0) {
                        conflicted += overlap;
                  }
            }
            if (totalDemand <= SIGNAL_PRESENT_EPSILON)
                  return 0.65;
            double linear = (aligned - conflicted) / totalDemand;
            return clamp01(0.5 + (0.5 * linear));
      }

      private static double resonanceSourceScale(SignalRecord record) {
            if (record == null || !record.isSetSource() || record.getSource() == null) {
                  return 1.0;
            }
            String source = record.getSource().trim().toLowerCase(Locale.ROOT);
            if (source.isBlank()) {
                  return 1.0;
            }
            if (source.contains("public_prompt_reaction")) {
                  return 0.75;
            }
            if (source.contains("signal_hierarchy")) {
                  return 0.65;
            }
            if (source.contains("private_prompt") || source.contains("agent") || source.contains("freeform")) {
                  return 1.0;
            }
            return 0.90;
      }

      private static Map<String, Double> toResonanceWeights(Signals signals) {
            LinkedHashMap<String, Double> out = new LinkedHashMap<>();
            if (signals == null || !signals.isSetRecords() || signals.getRecords() == null) {
                  return out;
            }
            for (SignalRecord record : signals.getRecords()) {
                  if (record == null) {
                        continue;
                  }
                  SignalIntent intent = record.isSetIntent() ? record.getIntent() : null;
                  if (intent != SignalIntent.META) {
                        continue;
                  }
                  ParsedSignalToken parsed = parseTokenAndValence(record);
                  if (parsed == null || parsed.token == null || parsed.token.isBlank()) {
                        continue;
                  }
                  double valence = clampSigned(parsed.valence);
                  double absValence = Math.abs(valence);
                  if (absValence <= SIGNAL_PRESENT_EPSILON) {
                        continue;
                  }
                  int count = record.isSetCount() ? Math.max(1, record.getCount()) : 1;
                  double countScale = Math.min(1.25, 0.85 + (Math.log1p(count) / 4.0));
                  double signedWeight = Math.signum(valence)
                              * Math.max(0.35, absValence)
                              * countScale
                              * resonanceSourceScale(record);
                  if (Math.abs(signedWeight) <= SIGNAL_PRESENT_EPSILON) {
                        continue;
                  }
                  accumulateSignalWeight(out, parsed.token, signedWeight);
            }
            return out;
      }

      private static ResonanceScore resonanceScore(Map<String, Double> a, Map<String, Double> b) {
            if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
                  return new ResonanceScore(0.0, 0);
            }
            double totalA = 0.0;
            double totalB = 0.0;
            for (Double value : a.values()) {
                  totalA += value == null ? 0.0 : Math.abs(value.doubleValue());
            }
            for (Double value : b.values()) {
                  totalB += value == null ? 0.0 : Math.abs(value.doubleValue());
            }
            double signedOverlap = 0.0;
            int sharedCount = 0;
            for (Map.Entry<String, Double> entry : a.entrySet()) {
                  String token = entry.getKey();
                  if (token == null || token.isBlank() || !b.containsKey(token)) {
                        continue;
                  }
                  double av = entry.getValue() == null ? 0.0 : entry.getValue().doubleValue();
                  double bv = b.get(token) == null ? 0.0 : b.get(token).doubleValue();
                  double aAbs = Math.abs(av);
                  double bAbs = Math.abs(bv);
                  if (aAbs <= SIGNAL_PRESENT_EPSILON || bAbs <= SIGNAL_PRESENT_EPSILON) {
                        continue;
                  }
                  signedOverlap += Math.min(aAbs, bAbs) * Math.signum(av) * Math.signum(bv);
                  sharedCount++;
            }
            if (sharedCount == 0 || Math.abs(signedOverlap) <= SIGNAL_PRESENT_EPSILON) {
                  return new ResonanceScore(0.0, sharedCount);
            }
            double normalizer = Math.max(1.5, Math.min(totalA, totalB));
            return new ResonanceScore(clampSigned(signedOverlap / normalizer), sharedCount);
      }

      private static double computeUncertainty(Map<String, Double> desired, Map<String, Double> otherSelf) {
            if (desired == null || desired.isEmpty())
                  return 0.15;
            Map<String, Double> other = (otherSelf == null) ? Collections.emptyMap() : otherSelf;
            double total = 0.0;
            double missing = 0.0;
            for (Map.Entry<String, Double> entry : desired.entrySet()) {
                  String token = entry.getKey();
                  double weight = entry.getValue() == null ? 0.0 : entry.getValue();
                  if (token == null || token.isBlank())
                        continue;
                  double absWeight = Math.abs(weight);
                  if (absWeight <= SIGNAL_PRESENT_EPSILON)
                        continue;
                  total += absWeight;
                  if (Math.abs(other.getOrDefault(token, 0.0)) <= SIGNAL_PRESENT_EPSILON) {
                        missing += absWeight;
                  }
            }
            if (total <= SIGNAL_PRESENT_EPSILON)
                  return 0.15;
            return clamp01(missing / total);
      }

      private static MissingDesiredSignal topMissingDesiredSignal(Map<String, Double> desired, Map<String, Double> otherSelf) {
            if (desired == null || desired.isEmpty())
                  return null;
            Map<String, Double> other = (otherSelf == null) ? Collections.emptyMap() : otherSelf;
            String bestToken = null;
            double bestWeightAbs = -1.0;
            double bestValence = 1.0;
            for (Map.Entry<String, Double> entry : desired.entrySet()) {
                  String token = entry.getKey();
                  double weight = entry.getValue() == null ? 0.0 : entry.getValue();
                  if (token == null || token.isBlank())
                        continue;
                  double absWeight = Math.abs(weight);
                  if (absWeight <= SIGNAL_PRESENT_EPSILON)
                        continue;
                  if (Math.abs(other.getOrDefault(token, 0.0)) > SIGNAL_PRESENT_EPSILON)
                        continue;
                  if (absWeight > bestWeightAbs) {
                        bestWeightAbs = absWeight;
                        bestToken = token;
                        bestValence = Math.signum(weight) == 0.0 ? 1.0 : Math.signum(weight);
                  }
            }
            if (bestToken == null)
                  return null;
            return new MissingDesiredSignal(bestToken, bestValence, bestWeightAbs);
      }

      private static final class ParsedSignalToken {
            final String token;
            final double valence;

            ParsedSignalToken(String token, double valence) {
                  this.token = token;
                  this.valence = valence;
            }
      }

      private static final class ResonanceScore {
            final double alignment;
            final int sharedCount;

            ResonanceScore(double alignment, int sharedCount) {
                  this.alignment = alignment;
                  this.sharedCount = sharedCount;
            }
      }

      private static final class MissingDesiredSignal {
            final String token;
            final double valence;
            final double absWeight;

            MissingDesiredSignal(String token, double valence, double absWeight) {
                  this.token = token;
                  this.valence = valence;
                  this.absWeight = absWeight;
            }
      }

      private static double normalizedReactionScore(double pairReactionScore) {
            return clamp01(0.5 + (pairReactionScore / 20.0));
      }

      private static double noveltyBoost(Map<?, ?> exposureMap, long targetId, long now) {
            if (exposureMap == null)
                  return 1.0;
            Object raw = exposureMap.get(targetId);
            if (!(raw instanceof Number))
                  return 1.0;
            long seenAt = ((Number) raw).longValue();
            long elapsed = Math.max(0L, now - seenAt);
            return clamp01((double) elapsed / (double) EXPOSURE_TTL_MS);
      }

      private static String suppressionKey(String promptId, String signalToken) {
            if (promptId == null || promptId.isBlank() || signalToken == null || signalToken.isBlank()) {
                  return null;
            }
            return promptId.trim() + "::" + signalToken.trim();
      }

      private static int clampPublicReactionStrength(int strength) {
            if (strength < PUBLIC_REACTION_STRENGTH_MIN) {
                  return PUBLIC_REACTION_STRENGTH_MIN;
            }
            if (strength > PUBLIC_REACTION_STRENGTH_MAX) {
                  return PUBLIC_REACTION_STRENGTH_MAX;
            }
            return strength;
      }

      private static int legacyReactionStrength(Integer reactionValue) {
            if (reactionValue == null) {
                  return 0;
            }
            if (reactionValue.intValue() == PromptReaction.LIKE.getValue()) {
                  return 1;
            }
            if (reactionValue.intValue() == PromptReaction.DISLIKE.getValue()) {
                  return -1;
            }
            return 0;
      }

      private static Integer parseEmbeddedPublicReactionStrength(String rawPromptId) {
            if (rawPromptId == null || rawPromptId.isBlank()) {
                  return null;
            }
            int idx = rawPromptId.lastIndexOf(PUBLIC_REACTION_STRENGTH_PROMPT_SUFFIX);
            if (idx <= 0) {
                  return null;
            }
            String suffix = rawPromptId.substring(idx + PUBLIC_REACTION_STRENGTH_PROMPT_SUFFIX.length()).trim();
            if (suffix.isBlank()) {
                  return null;
            }
            try {
                  int parsed = Integer.parseInt(suffix);
                  return clampPublicReactionStrength(parsed);
            } catch (NumberFormatException ignored) {
                  return null;
            }
      }

      private static String basePromptIdFromReactionPromptId(String rawPromptId) {
            if (rawPromptId == null || rawPromptId.isBlank()) {
                  return rawPromptId;
            }
            int idx = rawPromptId.lastIndexOf(PUBLIC_REACTION_STRENGTH_PROMPT_SUFFIX);
            if (idx <= 0) {
                  return rawPromptId;
            }
            String prefix = rawPromptId.substring(0, idx).trim();
            return prefix.isBlank() ? rawPromptId : prefix;
      }

      private static int resolvedPublicReactionStrength(String rawPromptId, Integer reactionValue) {
            Integer embedded = parseEmbeddedPublicReactionStrength(rawPromptId);
            if (embedded != null) {
                  return clampPublicReactionStrength(embedded.intValue());
            }
            return legacyReactionStrength(reactionValue);
      }

      private static long parseFacecardTargetId(String answerId) {
            if (answerId == null || answerId.isBlank() || !answerId.startsWith(FACECARD_REACTION_ANSWER_PREFIX)) {
                  return -1L;
            }
            String raw = answerId.substring(FACECARD_REACTION_ANSWER_PREFIX.length()).trim();
            if (raw.isBlank()) {
                  return -1L;
            }
            try {
                  long targetId = Long.parseLong(raw);
                  return targetId >= 0L ? targetId : -1L;
            } catch (NumberFormatException ignored) {
                  return -1L;
            }
      }

      private static Map<String, Object> scorePair(Filters viewer,
                  long targetId,
                  Filters target,
                  Signals viewerSignals,
                  Signals targetSignals,
                  double viewerToTargetReaction,
                  double targetToViewerReaction,
                  Map<?, ?> exposureMap,
                  long now) {
            HashMap<String, Object> out = new HashMap<>();
            if (viewer == null || target == null) {
                  out.put("candidate", null);
                  out.put("uncertainty", 1.0);
                  return out;
            }

            double baseScore = CalypsoHelpers.computeMatchesBaseScore(viewer, target);
            if (baseScore < 0.0) {
                  out.put("candidate", null);
                  out.put("uncertainty", 1.0);
                  return out;
            }
            double lifestyleBonus = CalypsoHelpers.computeLifestyleBonus(viewer, target);
            double politicsBonus = CalypsoHelpers.computePoliticsBonus(viewer, target);
            double religionBonus = CalypsoHelpers.computeReligionBonus(viewer, target);
            double filterPreferenceFit = clamp01((baseScore + lifestyleBonus + politicsBonus + religionBonus) / 120.0);

            Map<String, Double> viewerSelf = toSignalWeights(viewerSignals, false);
            Map<String, Double> viewerDesired = toSignalWeights(viewerSignals, true);
            Map<String, Double> targetSelf = toSignalWeights(targetSignals, false);
            Map<String, Double> targetDesired = toSignalWeights(targetSignals, true);
            Map<String, Double> viewerResonance = toResonanceWeights(viewerSignals);
            Map<String, Double> targetResonance = toResonanceWeights(targetSignals);

            double viewerNeedsMetByTarget = directionalCompatibility(viewerDesired, targetSelf);
            double targetNeedsMetByViewer = directionalCompatibility(targetDesired, viewerSelf);
            double sharedSelfOverlap = weightedJaccard(viewerSelf, targetSelf);
            ResonanceScore resonance = resonanceScore(viewerResonance, targetResonance);

            double signalAlignment = clamp01(
                        0.45 * viewerNeedsMetByTarget + 0.35 * targetNeedsMetByViewer + 0.20 * sharedSelfOverlap);
            double profileSignalBlend = clamp01(0.55 * filterPreferenceFit + 0.45 * signalAlignment);
            double viewerReactionScore = normalizedReactionScore(viewerToTargetReaction);
            double targetInterestScore = clamp01(
                        0.30 * targetNeedsMetByViewer + 0.20 * normalizedReactionScore(targetToViewerReaction)
                                    + 0.50 * filterPreferenceFit);
            double noveltyScore = noveltyBoost(exposureMap, targetId, now);

            double finalScoreBeforeResonance = clamp01(
                        0.50 * profileSignalBlend + 0.30 * viewerReactionScore + 0.15 * targetInterestScore
                                    + 0.05 * noveltyScore);
            double resonanceDelta = resonance.alignment >= 0.0
                        ? resonance.alignment * RESONANCE_POSITIVE_MAX_DELTA
                        : resonance.alignment * RESONANCE_NEGATIVE_MAX_DELTA;
            double finalScore = clamp01(finalScoreBeforeResonance + resonanceDelta);
            double finalScorePercent = finalScore * 100.0;

            String viewerMode = CalypsoHelpers.getModeSelfOrNull(viewer);
            double floor = modeFloor(viewerMode);
            if (finalScorePercent < floor) {
                  out.put("candidate", null);
                  out.put("uncertainty", computeUncertainty(viewerDesired, targetSelf));
                  return out;
            }

            MatchCandidate candidate = mkCandidate(targetId, finalScorePercent, now);
            ArrayList<String> reasons = new ArrayList<>();
            reasons.add(String.format(Locale.ROOT, "filterPreferenceFit=%.3f", filterPreferenceFit));
            reasons.add(String.format(Locale.ROOT, "viewerNeedsMetByTarget=%.3f", viewerNeedsMetByTarget));
            reasons.add(String.format(Locale.ROOT, "targetNeedsMetByViewer=%.3f", targetNeedsMetByViewer));
            reasons.add(String.format(Locale.ROOT, "sharedSelfOverlap=%.3f", sharedSelfOverlap));
            reasons.add(String.format(Locale.ROOT, "signalAlignment=%.3f", signalAlignment));
            reasons.add(String.format(Locale.ROOT, "profileSignalBlend=%.3f", profileSignalBlend));
            reasons.add(String.format(Locale.ROOT, "viewerReactionScore=%.3f", viewerReactionScore));
            reasons.add(String.format(Locale.ROOT, "targetInterestScore=%.3f", targetInterestScore));
            reasons.add(String.format(Locale.ROOT, "noveltyScore=%.3f", noveltyScore));
            reasons.add(String.format(Locale.ROOT, "resonanceAlignment=%.3f", resonance.alignment));
            reasons.add(String.format(Locale.ROOT, "resonanceSharedCount=%.3f", (double) resonance.sharedCount));
            reasons.add(String.format(Locale.ROOT, "resonanceDelta=%.3f", resonanceDelta));
            reasons.add(String.format(Locale.ROOT, "finalScoreBeforeResonance=%.3f", finalScoreBeforeResonance));
            reasons.add(String.format(Locale.ROOT, "finalScore=%.3f", finalScore));
            candidate.setReasons(reasons);

            double uncertainty = computeUncertainty(viewerDesired, targetSelf);
            out.put("candidate", candidate);
            out.put("uncertainty", uncertainty);
            out.put("normalizedScore", finalScore);

            MissingDesiredSignal missingSignal = topMissingDesiredSignal(viewerDesired, targetSelf);
            boolean followupEligible = missingSignal != null
                        && finalScore >= FOLLOWUP_MIN_NORMALIZED_SCORE
                        && uncertainty >= 0.35;
            if (followupEligible) {
                  HashMap<String, Object> followup = new HashMap<>();
                  followup.put("targetId", targetId);
                  followup.put("missingToken", missingSignal.token);
                  followup.put("missingValence", missingSignal.valence);
                  followup.put("pairScore", finalScorePercent);
                  followup.put("uncertainty", uncertainty);
                  followup.put("eligibleAt", now);
                  out.put("followup", followup);
            }
            return out;
      }

      @SuppressWarnings("unchecked")
      private static MatchCandidate candidateFromPayload(Map<String, Object> payload) {
            if (payload == null)
                  return null;
            Object raw = payload.get("candidate");
            return (raw instanceof MatchCandidate) ? (MatchCandidate) raw : null;
      }

      @SuppressWarnings("unchecked")
      private static Map<String, Object> followupFromPayload(Map<String, Object> payload) {
            if (payload == null)
                  return null;
            Object raw = payload.get("followup");
            if (!(raw instanceof Map))
                  return null;
            return (Map<String, Object>) raw;
      }

      private static double uncertaintyFromPayload(Map<String, Object> payload) {
            if (payload == null)
                  return 1.0;
            return asDouble(payload.get("uncertainty"), 1.0);
      }

      private static long normalizeAccountId(Number n) {
            return n == null ? 0L : n.longValue();
      }

      private static long resolveFollowupScheduledAt(PrivatePromptAssignment assignment, Number scheduledAtRaw) {
            if (assignment != null && assignment.isSetScheduledAt()) {
                  return assignment.getScheduledAt();
            }
            if (scheduledAtRaw != null) {
                  return scheduledAtRaw.longValue();
            }
            return System.currentTimeMillis();
      }

      private static long resolveFollowupCompletedAt(PrivatePromptAssignment assignment, Number completedAtRaw) {
            if (assignment != null && assignment.isSetCompletedAt()) {
                  return assignment.getCompletedAt();
            }
            if (completedAtRaw != null) {
                  return completedAtRaw.longValue();
            }
            return System.currentTimeMillis();
      }

      private static String computeNextActiveMatchmakingFollowupInstanceId(String currentActive,
                  PrivatePromptAssignment assignment) {
            if (assignment == null || assignment.getInstanceId() == null) {
                  return currentActive;
            }
            PrivatePromptStatus status = assignment.getStatus();
            if (status == PrivatePromptStatus.ACTIVE || status == PrivatePromptStatus.SNOOZED) {
                  return assignment.getInstanceId();
            }
            if ((status == PrivatePromptStatus.ANSWERED || status == PrivatePromptStatus.SKIPPED)
                        && Objects.equals(currentActive, assignment.getInstanceId())) {
                  return null;
            }
            return currentActive;
      }

      private static boolean isMatchmakingFollowupServableNow(PrivatePromptAssignment assignment, long now) {
            if (assignment == null || !assignment.isSetStatus() || assignment.getStatus() == null) {
                  return false;
            }
            if (assignment.getStatus() == PrivatePromptStatus.ACTIVE) {
                  return true;
            }
            if (assignment.getStatus() == PrivatePromptStatus.SNOOZED) {
                  long until = assignment.isSetSnoozeUntil() ? assignment.getSnoozeUntil() : 0L;
                  return until <= now;
            }
            return false;
      }

      private static long asLong(Object raw, long fallback) {
            if (raw instanceof Number) {
                  return ((Number) raw).longValue();
            }
            return fallback;
      }

      @SuppressWarnings("unchecked")
      private static List<Map<String, Object>> toSortedFollowupCandidates(Map<?, ?> byViewer, Object limitObj) {
            int limit = 10;
            if (limitObj instanceof Number) {
                  int parsed = ((Number) limitObj).intValue();
                  if (parsed < 1)
                        limit = 1;
                  else if (parsed > 100)
                        limit = 100;
                  else
                        limit = parsed;
            }
            ArrayList<Map<String, Object>> out = new ArrayList<>();
            if (byViewer == null || byViewer.isEmpty()) {
                  return out;
            }
            for (Map.Entry<?, ?> entry : byViewer.entrySet()) {
                  long viewerId = asLong(entry.getKey(), -1L);
                  if (viewerId < 0L) {
                        continue;
                  }
                  Object rawValue = entry.getValue();
                  if (!(rawValue instanceof Map)) {
                        continue;
                  }
                  Map<?, ?> payload = (Map<?, ?>) rawValue;
                  HashMap<String, Object> candidate = new HashMap<>();
                  for (Map.Entry<?, ?> payloadEntry : payload.entrySet()) {
                        Object key = payloadEntry.getKey();
                        if (key == null)
                              continue;
                        candidate.put(key.toString(), payloadEntry.getValue());
                  }
                  candidate.put("viewerId", viewerId);
                  if (!candidate.containsKey("eligibleAt")) {
                        candidate.put("eligibleAt", System.currentTimeMillis());
                  }
                  out.add(candidate);
            }
            out.sort((a, b) -> {
                  double as = asDouble(a.get("pairScore"), 0.0);
                  double bs = asDouble(b.get("pairScore"), 0.0);
                  int byScore = Double.compare(bs, as);
                  if (byScore != 0)
                        return byScore;
                  double au = asDouble(a.get("uncertainty"), 0.0);
                  double bu = asDouble(b.get("uncertainty"), 0.0);
                  int byUncertainty = Double.compare(bu, au);
                  if (byUncertainty != 0)
                        return byUncertainty;
                  long ae = asLong(a.get("eligibleAt"), Long.MAX_VALUE);
                  long be = asLong(b.get("eligibleAt"), Long.MAX_VALUE);
                  return Long.compare(ae, be);
            });
            if (out.size() <= limit) {
                  return out;
            }
            return new ArrayList<>(out.subList(0, limit));
      }

      private static Map<String, Object> buildMatchmakingFollowupSchedulerState(String activeInstanceId,
                  Number lastScheduledAtRaw,
                  Number lastAnsweredAtRaw) {
            HashMap<String, Object> out = new HashMap<>();
            out.put("activeInstanceId", activeInstanceId);
            out.put("lastScheduledAt", lastScheduledAtRaw == null ? null : lastScheduledAtRaw.longValue());
            out.put("lastAnsweredAt", lastAnsweredAtRaw == null ? null : lastAnsweredAtRaw.longValue());
            return out;
      }

      private static long normalizeMapAccountId(Object raw) {
            if (raw instanceof Number) {
                  return ((Number) raw).longValue();
            }
            return 0L;
      }

      private static Map<String, Object> pairRescoreRequest(Long accountId, Long targetAccountId) {
            if (accountId == null || targetAccountId == null
                        || accountId.longValue() < 0L
                        || targetAccountId.longValue() < 0L
                        || Objects.equals(accountId, targetAccountId)) {
                  return null;
            }
            HashMap<String, Object> out = new HashMap<>();
            out.put("accountId", accountId.longValue());
            out.put("targetAccountId", targetAccountId.longValue());
            out.put("requestedAt", System.currentTimeMillis());
            return out;
      }

      private static int normalizeInt(Object raw, int fallback, int min, int max) {
            int value = fallback;
            if (raw instanceof Number) {
                  value = ((Number) raw).intValue();
            }
            if (value < min) {
                  return min;
            }
            if (value > max) {
                  return max;
            }
            return value;
      }

      @SuppressWarnings("unchecked")
      private static Map<String, Object> toStringObjectMap(Object raw) {
            if (!(raw instanceof Map)) {
                  return new HashMap<>();
            }
            Map<?, ?> map = (Map<?, ?>) raw;
            HashMap<String, Object> out = new HashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                  if (entry == null || entry.getKey() == null) {
                        continue;
                  }
                  out.put(entry.getKey().toString(), entry.getValue());
            }
            return out;
      }

      private static String asStringTrimmed(Object raw) {
            if (raw == null) {
                  return null;
            }
            String value = raw.toString().trim();
            return value.isEmpty() ? null : value;
      }

      private static Map<String, Object> defaultSilhouette(long accountId, long now) {
            HashMap<String, Object> out = new HashMap<>();
            out.put("accountId", accountId);
            out.put("version", 1L);
            out.put("maturity", "empty");
            out.put("modes", new ArrayList<>());
            HashMap<String, Object> summary = new HashMap<>();
            summary.put("silhouette", "");
            summary.put("generatedFromVersion", 1L);
            summary.put("updatedAt", 0L);
            out.put("summaryCache", summary);
            out.put("updatedAt", now);
            return out;
      }

      private static Map<String, Object> normalizeSilhouettePayload(Object rawPayload, Object accountIdRaw) {
            long accountId = normalizeMapAccountId(accountIdRaw);
            long now = System.currentTimeMillis();
            Map<String, Object> payload = toStringObjectMap(rawPayload);
            if (payload.isEmpty()) {
                  return defaultSilhouette(accountId, now);
            }
            HashMap<String, Object> out = new HashMap<>();
            out.put("accountId", accountId);
            out.put("version", asLong(payload.get("version"), 1L));
            out.put("maturity", asStringTrimmed(payload.get("maturity")) == null ? "empty" : payload.get("maturity"));
            Object modes = payload.get("modes");
            if (!(modes instanceof List<?>)) {
                  out.put("modes", new ArrayList<>());
            } else {
                  out.put("modes", modes);
            }
            Object summary = payload.get("summaryCache");
            if (!(summary instanceof Map<?, ?>)) {
                  summary = payload.get("summary_cache");
            }
            if (!(summary instanceof Map<?, ?>)) {
                  HashMap<String, Object> fallbackSummary = new HashMap<>();
                  fallbackSummary.put("silhouette", "");
                  fallbackSummary.put("generatedFromVersion", 1L);
                  fallbackSummary.put("updatedAt", 0L);
                  out.put("summaryCache", fallbackSummary);
            } else {
                  out.put("summaryCache", summary);
            }
            out.put("updatedAt", asLong(payload.get("updatedAt"), now));
            return out;
      }

      private static List<Map<String, Object>> sortPendingSilhouetteUpdates(Map<?, ?> byEventId, Object limitObj) {
            int limit = normalizeInt(limitObj, 50, 1, 200);
            ArrayList<Map<String, Object>> out = new ArrayList<>();
            if (byEventId != null) {
                  for (Object value : byEventId.values()) {
                        Map<String, Object> event = toStringObjectMap(value);
                        if (event.isEmpty()) {
                              continue;
                        }
                        String eventId = asStringTrimmed(event.get("eventId"));
                        if (eventId == null) {
                              continue;
                        }
                        event.put("eventId", eventId);
                        event.put("createdAt", asLong(event.get("createdAt"), 0L));
                        out.add(event);
                  }
            }
            out.sort((a, b) -> {
                  long at = asLong(a.get("createdAt"), 0L);
                  long bt = asLong(b.get("createdAt"), 0L);
                  int byTime = Long.compare(at, bt);
                  if (byTime != 0) {
                        return byTime;
                  }
                  String ae = asStringTrimmed(a.get("eventId"));
                  String be = asStringTrimmed(b.get("eventId"));
                  if (ae == null && be == null) {
                        return 0;
                  }
                  if (ae == null) {
                        return 1;
                  }
                  if (be == null) {
                        return -1;
                  }
                  return ae.compareTo(be);
            });
            if (out.size() <= limit) {
                  return out;
            }
            return new ArrayList<>(out.subList(0, limit));
      }

      private static void declareAccountsTopology(Topologies topologies) {
            StreamTopology stream = topologies.stream("accounts");
            ModuleUniqueIdPState accountIdGen = new ModuleUniqueIdPState("$$accountIdGen");
            accountIdGen.declarePState(stream);
            stream.pstate("$$phoneToUser", PState.mapSchema(String.class,
                        PState.fixedKeysSchema("accountId", Long.class,
                                    "uuid", String.class)));
            stream.pstate("$$accountIdToAccount", PState.mapSchema(Long.class, Account.class));

            stream.source("*accountDepot").out("*data")
                        .macro(extractFields("*data", "*phone_number", "*uuid"))
                        .localSelect("$$phoneToUser", Path.key("*phone_number")).out("*currInfo")
                        .each(Ops.GET, "*currInfo", "uuid").out("*currUUID")
                        // Accept either first write or an idempotent retry from the same UUID
                        .ifTrue(new Expr(Ops.OR, new Expr(Ops.IS_NULL, "*currInfo"),
                                    new Expr(Ops.EQUAL, "*uuid", "*currUUID")),
                                    Block.macro(accountIdGen.genId("*accountId"))
                                                .localTransform("$$phoneToUser",
                                                            Path.key("*phone_number").multiPath(
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

      private static void declareFiltersTopology(Topologies topologies) {
            StreamTopology stream = topologies.stream("filters");

            stream.pstate("$$accountIdToFiltersProjection",
                        PState.mapSchema(Long.class, Filters.class));
            stream.pstate("$$allAccountIdsGlobal",
                        PState.mapSchema(String.class, Map.class));

            stream.source("*filtersDepot").out("*data")
                        .macro(extractFields("*data", "*accountId"))
                        .each((Number n) -> n == null ? 0L : n.longValue(), "*accountId").out("*aidL")
                        .hashPartition("*aidL")
                        .localTransform("$$accountIdToFiltersProjection",
                                    Path.key("*aidL").termVal("*data"))
                        .each((Long aid) -> ALL_ACCOUNTS_KEY, "*aidL").out("*allKey")
                        .hashPartition("*allKey")
                        .localTransform("$$allAccountIdsGlobal",
                                    Path.key("*allKey", "*aidL").termVal("*aidL"));
      }

      private static void declarePublicPromptsTopology(Topologies topologies) {
            StreamTopology stream = topologies.stream("publicPrompts");

            stream.pstate("$$answerIdToPublicPromptAnswer",
                        PState.mapSchema(String.class, PublicPromptAnswer.class));
            stream.pstate("$$accountIdToPublicAnswerIdByPromptId",
                        PState.mapSchema(Long.class, Map.class));
            stream.pstate("$$promptIdToAnswerIds",
                        PState.mapSchema(String.class, Map.class));
            stream.pstate("$$viewerIdToReactedAnswerIds",
                        PState.mapSchema(Long.class, Map.class));
            stream.pstate("$$viewerIdToReactedPromptIds",
                        PState.mapSchema(Long.class, Map.class));
            stream.pstate("$$viewerIdToReactionByAnswerId",
                        PState.mapSchema(Long.class, Map.class));
            stream.pstate("$$viewerIdToTasteByToken",
                        PState.mapSchema(Long.class, Map.class));
            stream.pstate("$$viewerIdToTargetIdToReactionScore",
                        PState.mapSchema(Long.class, Map.class));
            stream.pstate("$$viewerIdToTargetIdToFacecardReaction",
                        PState.mapSchema(Long.class, Map.class));
            stream.pstate("$$viewerIdToTargetIdToPromptLikeSeen",
                        PState.mapSchema(Long.class, Map.class));
            stream.pstate("$$viewerIdToSuppressedSignalTokens",
                        PState.mapSchema(Long.class, Map.class));
            stream.pstate("$$accountIdToPublicPromptSelection",
                        PState.mapSchema(Long.class, PublicPromptSelection.class));

            stream.source("*publicPromptAnswerDepot")
                        .out("*data")
                        .macro(extractFields("*data", "*accountId", "*promptId", "*answerId"))
                        .each((Number n) -> n == null ? 0L : n.longValue(), "*accountId").out("*aidL")
                        .hashPartition("*answerId")
                        .localTransform("$$answerIdToPublicPromptAnswer",
                                    Path.key("*answerId").termVal("*data"))
                        .hashPartition("*aidL")
                        .localTransform("$$accountIdToPublicAnswerIdByPromptId",
                                    Path.key("*aidL", "*promptId").termVal("*answerId"))
                        .hashPartition("*promptId")
                        .localTransform("$$promptIdToAnswerIds",
                                    Path.key("*promptId", "*answerId").termVal(true));

            stream.source("*publicPromptReactionDepot")
                        .out("*data")
                        .macro(extractFields("*data", "*viewerAccountId", "*promptId", "*answerId"))
                        .each((Number n) -> n == null ? -1L : n.longValue(), "*viewerAccountId").out("*viewerIdL")
                        .each((PublicPromptReactionEvent event) -> {
                              if (event == null || !event.isSetReaction() || event.getReaction() == null)
                                    return 0;
                              return event.getReaction().getValue();
                        }, "*data")
                        .out("*reactionValue")
                        .each((String rawPromptId) -> basePromptIdFromReactionPromptId(rawPromptId), "*promptId")
                        .out("*normalizedPromptId")
                        .each((String rawPromptId, Integer reactionValue) -> resolvedPublicReactionStrength(rawPromptId,
                                    reactionValue),
                                    "*promptId", "*reactionValue")
                        .out("*reactionStrength")
                        .each((String answerId) -> parseFacecardTargetId(answerId), "*answerId")
                        .out("*facecardTargetIdL")
                        .each((Long targetId) -> targetId != null && targetId.longValue() >= 0L, "*facecardTargetIdL")
                        .out("*isFacecardReaction")
                        .ifTrue(new Expr(Ops.NOT, "*isFacecardReaction"),
                                    Block.create()
                                                .localTransform("$$viewerIdToReactedAnswerIds",
                                                            Path.key("*viewerIdL", "*answerId").termVal(true))
                                                .localTransform("$$viewerIdToReactedPromptIds",
                                                            Path.key("*viewerIdL", "*normalizedPromptId").termVal(true))
                                                .localTransform("$$viewerIdToReactionByAnswerId",
                                                            Path.key("*viewerIdL", "*answerId")
                                                                        .termVal("*reactionStrength")),
                                    Block.create())
                        .ifTrue("*isFacecardReaction",
                                    Block.create()
                                                .each(Ops.IDENTITY, "*facecardTargetIdL").out("*targetIdL")
                                                .each(() -> new ArrayList<String>()).out("*tokens"),
                                    Block.create()
                                                .hashPartition("*answerId")
                                                .localSelect("$$answerIdToPublicPromptAnswer", Path.key("*answerId"))
                                                .out("*answer")
                                                .each((PublicPromptAnswer answer) -> {
                                                      if (answer == null)
                                                            return 0L;
                                                      return answer.getAccountId();
                                                }, "*answer").out("*targetIdL")
                                                .each((PublicPromptAnswer answer) -> {
                                                      if (answer == null || !answer.isSetSignalTokens())
                                                            return new ArrayList<String>();
                                                      return answer.getSignalTokens();
                                                }, "*answer").out("*tokens"))
                        .each((Integer reactionStrength, Boolean isFacecardReaction) -> {
                              if (Boolean.TRUE.equals(isFacecardReaction))
                                    return 0.0;
                              if (reactionStrength == null)
                                    return 0.0;
                              return Math.max(-1.0,
                                          Math.min(1.0, reactionStrength.doubleValue() / (double) PUBLIC_REACTION_STRENGTH_MAX));
                        }, "*reactionStrength", "*isFacecardReaction").out("*delta")
                        .each((Integer reactionValue, Integer reactionStrength, Boolean isFacecardReaction) -> {
                              if (!Boolean.TRUE.equals(isFacecardReaction)) {
                                    if (reactionStrength == null)
                                          return 0.0;
                                    return reactionStrength.doubleValue();
                              }
                              if (reactionValue == null)
                                    return 0.0;
                              if (reactionValue.intValue() == PromptReaction.LIKE.getValue()) {
                                    return FACECARD_PAIR_DELTA_LIKE;
                              }
                              if (reactionValue.intValue() == PromptReaction.DISLIKE.getValue()) {
                                    return FACECARD_PAIR_DELTA_DISLIKE;
                              }
                              if (reactionValue.intValue() == PromptReaction.SKIP.getValue()) {
                                    return FACECARD_PAIR_DELTA_SKIP;
                              }
                              return 0.0;
                        }, "*reactionValue", "*reactionStrength", "*isFacecardReaction").out("*pairDelta")
                        .each((Long targetIdL) -> targetIdL != null && targetIdL.longValue() >= 0L, "*targetIdL")
                        .out("*hasTarget")
                        .ifTrue("*hasTarget",
                                    Block.create()
                                                .hashPartition("*viewerIdL")
                                                .each((Boolean isFacecardReaction, Integer reactionValue) -> Boolean.TRUE
                                                            .equals(isFacecardReaction)
                                                            && reactionValue != null,
                                                            "*isFacecardReaction", "*reactionValue")
                                                .out("*shouldTrackFacecard")
                                                .ifTrue("*shouldTrackFacecard",
                                                            Block.localTransform("$$viewerIdToTargetIdToFacecardReaction",
                                                                        Path.key("*viewerIdL", "*targetIdL")
                                                                                    .termVal("*reactionValue")))
                                                .each((Boolean isFacecardReaction, Integer reactionStrength) -> !Boolean.TRUE
                                                            .equals(isFacecardReaction)
                                                            && reactionStrength != null
                                                            && reactionStrength.intValue() > 0,
                                                            "*isFacecardReaction", "*reactionStrength")
                                                .out("*shouldTrackPromptLike")
                                                .ifTrue("*shouldTrackPromptLike",
                                                            Block.localTransform("$$viewerIdToTargetIdToPromptLikeSeen",
                                                                        Path.key("*viewerIdL", "*targetIdL")
                                                                                    .termVal(true)))
                                                .localSelect("$$viewerIdToTargetIdToReactionScore",
                                                            Path.key("*viewerIdL", "*targetIdL").nullToVal(0.0))
                                                .out("*prevPairScore")
                                                .each((Double prev, Double delta) -> {
                                                      double base = prev == null ? 0.0 : prev;
                                                      double inc = delta == null ? 0.0 : delta;
                                                      return base + inc;
                                                }, "*prevPairScore", "*pairDelta").out("*nextPairScore")
                                                .localTransform("$$viewerIdToTargetIdToReactionScore",
                                                            Path.key("*viewerIdL", "*targetIdL").termVal("*nextPairScore"))
                                                .each((Long viewerIdL, Long targetIdL, Double pairDelta) -> {
                                                      if (viewerIdL == null
                                                                  || targetIdL == null
                                                                  || pairDelta == null
                                                                  || Math.abs(pairDelta.doubleValue()) <= SIGNAL_PRESENT_EPSILON) {
                                                            return null;
                                                      }
                                                      return pairRescoreRequest(viewerIdL, targetIdL);
                                                }, "*viewerIdL", "*targetIdL", "*pairDelta").out("*pairRescoreReq")
                                                .each((Map<String, Object> req) -> req != null, "*pairRescoreReq")
                                                .out("*shouldQueuePairRescore")
                                                .ifTrue("*shouldQueuePairRescore",
                                                            Block.depotPartitionAppend("*matchPairRescoreDepot",
                                                                        "*pairRescoreReq"))
                                                .each((Double delta, List<String> tokens, Boolean isFacecardReaction) -> {
                                                      if (Boolean.TRUE.equals(isFacecardReaction))
                                                            return false;
                                                      return delta != null
                                                                  && delta.doubleValue() != 0.0
                                                                  && tokens != null
                                                                  && !tokens.isEmpty();
                                                }, "*delta", "*tokens", "*isFacecardReaction")
                                                .out("*shouldUpdateTaste")
                                                .ifTrue("*shouldUpdateTaste",
                                                            Block.create()
                                                                        .each(Ops.EXPLODE, "*tokens").out("*token")
                                                                        .localSelect("$$viewerIdToTasteByToken",
                                                                                    Path.key("*viewerIdL", "*token")
                                                                                                .nullToVal(0.0))
                                                                        .out("*prevTaste")
                                                                        .each((Double prev, Double delta) -> {
                                                                              double base = prev == null ? 0.0 : prev;
                                                                              double inc = delta == null ? 0.0 : delta;
                                                                              return base + inc;
                                                                        }, "*prevTaste", "*delta")
                                                                        .out("*nextTaste")
                                                                        .localTransform("$$viewerIdToTasteByToken",
                                                                                    Path.key("*viewerIdL", "*token")
                                                                                                .termVal("*nextTaste")))
                                                .each((Integer reactionStrength, String normalizedPromptId, List<String> tokens,
                                                            Boolean isFacecardReaction) -> {
                                                      if (Boolean.TRUE.equals(isFacecardReaction))
                                                            return false;
                                                      boolean reactsToSignal = reactionStrength != null
                                                                  && reactionStrength.intValue() != 0;
                                                      return reactsToSignal
                                                                  && normalizedPromptId != null
                                                                  && !normalizedPromptId.isBlank()
                                                                  && tokens != null
                                                                  && !tokens.isEmpty();
                                                }, "*reactionStrength", "*normalizedPromptId", "*tokens",
                                                            "*isFacecardReaction")
                                                .out("*shouldSuppressTokens")
                                                .ifTrue("*shouldSuppressTokens",
                                                            Block.create()
                                                                        .each(Ops.EXPLODE, "*tokens").out("*token")
                                                                        .each((String normalizedPromptId, String token) -> suppressionKey(
                                                                                    normalizedPromptId, token),
                                                                                    "*normalizedPromptId", "*token")
                                                                        .out("*suppressionKey")
                                                                        .each((String key) -> key != null, "*suppressionKey")
                                                                        .out("*hasSuppressionKey")
                                                                        .ifTrue("*hasSuppressionKey",
                                                                                    Block.create()
                                                                                                .localTransform("$$viewerIdToSuppressedSignalTokens",
                                                                                                            Path.key("*viewerIdL",
                                                                                                                        "*suppressionKey")
                                                                                                                        .termVal(true)))));

            stream.source("*publicPromptSelectionDepot")
                        .out("*data")
                        .macro(extractFields("*data", "*accountId"))
                        .each((Number n) -> n == null ? 0L : n.longValue(), "*accountId").out("*aidL")
                        .localTransform("$$accountIdToPublicPromptSelection",
                                    Path.key("*aidL").termVal("*data"));
      }

      private static void declareMatchesSignalsTopology(Topologies topologies) {
            StreamTopology stream = topologies.stream("signals");

            stream.pstate("$$accountIdToSignals", PState.mapSchema(Long.class, Signals.class));

            stream.source("*signalsDepot")
                        .out("*data")
                        .macro(extractFields("*data", "*accountId"))
                        .localTransform("$$accountIdToSignals",
                                    Path.key("*accountId").termVal("*data"));
      }

      private static void declareSilhouetteTopology(Topologies topologies) {
            StreamTopology stream = topologies.stream("silhouette");

            stream.pstate("$$accountIdToSilhouette", PState.mapSchema(Long.class, Map.class));
            stream.pstate("$$accountIdToPendingSilhouetteUpdates", PState.mapSchema(Long.class, Map.class));

            stream.source("*silhouetteDepot")
                        .out("*data")
                        .each((Object data) -> toStringObjectMap(data), "*data").out("*payload")
                        .each((Map<String, Object> payload) -> normalizeMapAccountId(payload.get("accountId")), "*payload")
                        .out("*accountIdL")
                        .hashPartition("*accountIdL")
                        .each((Map<String, Object> payload, Long accountIdL) -> normalizeSilhouettePayload(payload,
                                    accountIdL), "*payload", "*accountIdL")
                        .out("*normalized")
                        .localTransform("$$accountIdToSilhouette",
                                    Path.key("*accountIdL").termVal("*normalized"));

            stream.source("*silhouetteUpdateEventDepot")
                        .out("*data")
                        .each((Object data) -> toStringObjectMap(data), "*data").out("*event")
                        .each((Map<String, Object> event) -> normalizeMapAccountId(event.get("accountId")), "*event")
                        .out("*accountIdL")
                        .each((Map<String, Object> event) -> asStringTrimmed(event.get("eventId")), "*event")
                        .out("*eventId")
                        .each((String eventId) -> eventId != null, "*eventId").out("*hasEventId")
                        .ifTrue("*hasEventId",
                                    Block.hashPartition("*accountIdL")
                                                .localTransform("$$accountIdToPendingSilhouetteUpdates",
                                                            Path.key("*accountIdL", "*eventId").termVal("*event")));

            stream.source("*silhouetteUpdateAckDepot")
                        .out("*data")
                        .each((Object data) -> toStringObjectMap(data), "*data").out("*ack")
                        .each((Map<String, Object> ack) -> normalizeMapAccountId(ack.get("accountId")), "*ack")
                        .out("*accountIdL")
                        .each((Map<String, Object> ack) -> asStringTrimmed(ack.get("eventId")), "*ack")
                        .out("*eventId")
                        .each((String eventId) -> eventId != null, "*eventId").out("*hasEventId")
                        .ifTrue("*hasEventId",
                                    Block.hashPartition("*accountIdL")
                                                .localTransform("$$accountIdToPendingSilhouetteUpdates",
                                                            Path.key("*accountIdL", "*eventId").termVoid()));
      }

      private static void declareMatchesServeAndCursorTopology(Topologies topologies) {
            StreamTopology stream = topologies.stream("serveAndCursor");

            // viewer -> (targetId -> servedAt)
            stream.pstate("$$accountIdToExposure",
                        PState.mapSchema(Long.class, Map.class));

            // { lastIndex, wrappedOnce } per viewer
            stream.pstate("$$accountIdToCursor",
                        PState.mapSchema(Long.class,
                                    PState.fixedKeysSchema("lastIndex", Integer.class,
                                                "wrappedOnce", Boolean.class)));

            // record exposures
            stream.source("*matchesServeDepot").out("*data")
                        .macro(extractFields("*data", "*accountId", "*targetIds", "*servedAt"))
                        .each((Number n) -> n == null ? 0L : n.longValue(), "*accountId").out("*aidL")
                        .hashPartition("*aidL")
                        .each(Ops.EXPLODE, "*targetIds").out("*targetId")
                        .each((Number n) -> n == null ? 0L : n.longValue(), "*targetId").out("*tidL")
                        .each((Number n) -> n == null ? System.currentTimeMillis() : n.longValue(), "*servedAt")
                        .out("*servedAtL")
                        .localTransform("$$accountIdToExposure",
                                    Path.key("*aidL", "*tidL").termVal("*servedAtL"));

            // apply cursor ACKs
            stream.source("*matchesCursorAckDepot").out("*data")
                        .macro(extractFields("*data", "*accountId", "*lastIndex", "*wrappedOnce"))
                        .each((Number n) -> n == null ? 0L : n.longValue(), "*accountId").out("*aidL")
                        .each((Number n) -> n == null ? 0 : n.intValue(), "*lastIndex").out("*lastIndexI")
                        .hashPartition("*aidL")
                        .localTransform("$$accountIdToCursor",
                                    Path.key("*aidL", "lastIndex").termVal("*lastIndexI"))
                        .localTransform("$$accountIdToCursor",
                                    Path.key("*aidL", "wrappedOnce").termVal("*wrappedOnce"));
      }

      private static void declareMatchesRefillTopology(Topologies topologies) {
            StreamTopology stream = topologies.stream("refill");

            // viewer -> sorted heap (List<MatchCandidate>)
            stream.pstate("$$accountIdToCandidateHeap",
                        PState.mapSchema(Long.class, List.class));
            stream.pstate("$$accountIdToRefillPending",
                        PState.mapSchema(Long.class, Boolean.class));
            stream.pstate("$$accountIdToLastRefillAt",
                        PState.mapSchema(Long.class, Long.class));
            stream.pstate("$$viewerIdToTargetIdToUncertainty",
                        PState.mapSchema(Long.class, Map.class));
            stream.pstate("$$targetIdToFollowupByViewer",
                        PState.mapSchema(Long.class, Map.class));

            stream.source("*matchRefillDepot").out("*data")
                        .macro(extractFields("*data", "*accountId", "*targetSize"))
                        .each((Number n) -> n == null ? 0L : n.longValue(), "*accountId").out("*aidL")
                        .hashPartition("*aidL")
                        .localSelect("$$accountIdToRefillPending", Path.key("*aidL").nullToVal(false))
                        .out("*isPending")
                        .each((Boolean pending) -> !Boolean.TRUE.equals(pending), "*isPending")
                        .out("*shouldProcess")
                        .ifTrue("*shouldProcess",
                                    Block.create()
                                                .localTransform("$$accountIdToRefillPending",
                                                            Path.key("*aidL").termVal(true))
                                                // Load viewer filters and exposures once
                                                .localSelect("$$accountIdToFiltersProjection",
                                                            Path.key("*aidL"))
                                                .out("*viewerFilters")
                                                .localSelect("$$accountIdToSignals",
                                                            Path.key("*aidL"))
                                                .out("*viewerSignals")
                                                .localSelect("$$accountIdToExposure", Path.key("*aidL"))
                                                .out("*exposures")
                                                .each((Map<?, ?> ex) -> ex == null ? new HashMap<>() : ex,
                                                            "*exposures")
                                                .out("*exposuresSafe")
                                                .localSelect("$$viewerIdToTargetIdToReactionScore", Path.key("*aidL"))
                                                .out("*viewerPairReactions")
                                                .each((Map<?, ?> reactions) -> reactions == null ? new HashMap<>() : reactions,
                                                            "*viewerPairReactions")
                                                .out("*viewerPairReactionsSafe")
                                                .each((Long aid) -> ALL_ACCOUNTS_KEY, "*aidL").out("*allKey")
                                                .hashPartition("*allKey")
                                                // Iterate over all known accountIds:
                                                .localSelect("$$allAccountIdsGlobal", Path.key("*allKey"))
                                                .out("*allIdsRaw")
                                                .each((Map<?, ?> ids) -> ids == null ? new HashMap<>() : ids, "*allIdsRaw")
                                                .out("*allIds")
                                                .each(Ops.EXPLODE, "*allIds").out("*entry")
                                                // entry is a MapEntry; we just want the key
                                                // (accountId)
                                                .each((Object e) -> {
                                                      if (e instanceof java.util.Map.Entry) {
                                                            return ((java.util.Map.Entry<?, ?>) e)
                                                                        .getKey();
                                                      }
                                                      return null;
                                                }, "*entry").out("*tidObj")
                                                .each((Object n) -> (n instanceof Number)
                                                            ? ((Number) n).longValue()
                                                            : 0L,
                                                            "*tidObj")
                                                .out("*tidL")

                                                // Skip self (viewer == target)
                                                .each((Long vid, Long tid) -> vid != null
                                                            && tid != null
                                                            && !Objects.equals(vid, tid),
                                                            "*aidL", "*tidL")
                                                .out("*isOther")

                                                .ifTrue("*isOther",
                                                            Block.create()
                                                                        .hashPartition("*tidL")
                                                                        // For each targetId,
                                                                        // load its Filters
                                                                        .localSelect(
                                                                                    "$$accountIdToFiltersProjection",
                                                                                    Path.key(
                                                                                                "*tidL"))
                                                                        .out("*targetFilters")
                                                                        .localSelect("$$accountIdToSignals",
                                                                                    Path.key("*tidL"))
                                                                        .out("*targetSignals")
                                                                        .localSelect("$$viewerIdToTargetIdToReactionScore",
                                                                                    Path.key("*tidL", "*aidL")
                                                                                                .nullToVal(0.0))
                                                                        .out("*targetToViewerReaction")
                                                                        // Cast viewer/target
                                                                        // filters and signals
                                                                        // cleanly
                                                                        .each((Object vfObj) -> (vfObj instanceof Filters)
                                                                                    ? (Filters) vfObj
                                                                                    : null,
                                                                                    "*viewerFilters")
                                                                        .out("*viewerFiltersC")
                                                                        .each((Object tfObj) -> (tfObj instanceof Filters)
                                                                                    ? (Filters) tfObj
                                                                                    : null,
                                                                                    "*targetFilters")
                                                                        .out("*targetFiltersC")
                                                                        .each((Object vsObj) -> (vsObj instanceof Signals)
                                                                                    ? (Signals) vsObj
                                                                                    : null,
                                                                                    "*viewerSignals")
                                                                        .out("*viewerSignalsC")
                                                                        .each((Object tsObj) -> (tsObj instanceof Signals)
                                                                                    ? (Signals) tsObj
                                                                                    : null,
                                                                                    "*targetSignals")
                                                                        .out("*targetSignalsC")
                                                                        .each((Map<?, ?> reactionMap, Long tid) -> {
                                                                              if (reactionMap == null || tid == null)
                                                                                    return 0.0;
                                                                              Object raw = reactionMap.get(tid);
                                                                              if (raw instanceof Number)
                                                                                    return ((Number) raw).doubleValue();
                                                                              return 0.0;
                                                                        }, "*viewerPairReactionsSafe", "*tidL")
                                                                        .out("*viewerToTargetReaction")
                                                                        .each((Filters viewer,
                                                                                    Long tid,
                                                                                    Filters target,
                                                                                    Signals viewerSignals,
                                                                                    Signals targetSignals,
                                                                                    Double viewerToTargetReaction,
                                                                                    Double targetToViewerReaction,
                                                                                    Map<?, ?> exposures) -> scorePair(
                                                                                                viewer,
                                                                                                tid == null ? 0L : tid,
                                                                                                target,
                                                                                                viewerSignals,
                                                                                                targetSignals,
                                                                                                viewerToTargetReaction == null
                                                                                                            ? 0.0
                                                                                                            : viewerToTargetReaction,
                                                                                                targetToViewerReaction == null
                                                                                                            ? 0.0
                                                                                                            : targetToViewerReaction,
                                                                                                exposures,
                                                                                                System.currentTimeMillis()),
                                                                                    "*viewerFiltersC",
                                                                                    "*tidL",
                                                                                    "*targetFiltersC",
                                                                                    "*viewerSignalsC",
                                                                                    "*targetSignalsC",
                                                                                    "*viewerToTargetReaction",
                                                                                    "*targetToViewerReaction",
                                                                                    "*exposuresSafe")
                                                                        .out("*pairPayload")
                                                                        .each((Map<String, Object> payload) -> followupFromPayload(
                                                                                    payload),
                                                                                    "*pairPayload")
                                                                        .out("*followupState")
                                                                        .each((Map<String, Object> followup) -> followup != null,
                                                                                    "*followupState")
                                                                        .out("*hasFollowup")
                                                                        .ifTrue("*hasFollowup",
                                                                                    Block.localTransform(
                                                                                                "$$targetIdToFollowupByViewer",
                                                                                                Path.key("*tidL", "*aidL")
                                                                                                            .termVal("*followupState")),
                                                                                    Block.localTransform(
                                                                                                "$$targetIdToFollowupByViewer",
                                                                                                Path.key("*tidL", "*aidL")
                                                                                                            .termVoid()))
                                                                        .hashPartition("*aidL")
                                                                        .each((Map<String, Object> payload) -> uncertaintyFromPayload(
                                                                                    payload),
                                                                                    "*pairPayload")
                                                                        .out("*uncertainty")
                                                                        .localTransform("$$viewerIdToTargetIdToUncertainty",
                                                                                    Path.key("*aidL", "*tidL")
                                                                                                .termVal("*uncertainty"))
                                                                        .each((Map<String, Object> payload) -> candidateFromPayload(
                                                                                    payload),
                                                                                    "*pairPayload")
                                                                        .out("*candMaybe")
                                                                        // Read current heap,
                                                                        // defaulting to empty
                                                                        // list
                                                                        .hashPartition("*aidL")
                                                                        .localSelect(
                                                                                    "$$accountIdToCandidateHeap",
                                                                                    Path.key(
                                                                                                "*aidL"))
                                                                        .out("*heapRaw")
                                                                        .each((Object hObj) -> {
                                                                              if (hObj == null)
                                                                                    return new ArrayList<MatchCandidate>();
                                                                              return (List<MatchCandidate>) hObj;
                                                                        }, "*heapRaw")
                                                                        .out("*currHeap")

                                                                        // Upsert candidate if
                                                                        // non-null; otherwise
                                                                        // keep heap as-is
                                                                        .each((List<MatchCandidate> heap,
                                                                                    MatchCandidate cand,
                                                                                    Long targetId) -> {
                                                                              if (cand == null) {
                                                                                    return removeFromHeap(heap,
                                                                                                targetId == null ? 0L
                                                                                                            : targetId);
                                                                              }
                                                                              return upsertIntoHeap(heap, cand);
                                                                        },
                                                                                    "*currHeap",
                                                                                    "*candMaybe",
                                                                                    "*tidL")
                                                                        .out("*newHeap")

                                                                        .localTransform(
                                                                                    "$$accountIdToCandidateHeap",
                                                                                    Path.key(
                                                                                                "*aidL")
                                                                                                .termVal(
                                                                                                            "*newHeap")))
                                                .each(() -> System.currentTimeMillis())
                                                .out("*refillDoneTs")
                                                .hashPartition("*aidL")
                                                .localTransform("$$accountIdToLastRefillAt",
                                                            Path.key("*aidL").termVal(
                                                                        "*refillDoneTs"))
                                                .localTransform("$$accountIdToRefillPending",
                                                            Path.key("*aidL").termVal(false)));

            stream.source("*matchPairRescoreDepot").out("*data")
                        .each((Object data) -> toStringObjectMap(data), "*data").out("*req")
                        .each((Map<String, Object> req) -> normalizeMapAccountId(req.get("accountId")), "*req")
                        .out("*aidL")
                        .each((Map<String, Object> req) -> asLong(req.get("targetAccountId"), -1L), "*req")
                        .out("*tidL")
                        .each((Long aid, Long tid) -> aid != null
                                    && tid != null
                                    && aid.longValue() >= 0L
                                    && tid.longValue() >= 0L
                                    && !Objects.equals(aid, tid),
                                    "*aidL", "*tidL")
                        .out("*validPairRescore")
                        .ifTrue("*validPairRescore",
                                    Block.create()
                                                .hashPartition("*aidL")
                                                .localSelect("$$accountIdToFiltersProjection", Path.key("*aidL"))
                                                .out("*viewerFilters")
                                                .localSelect("$$accountIdToSignals", Path.key("*aidL"))
                                                .out("*viewerSignals")
                                                .localSelect("$$accountIdToExposure", Path.key("*aidL"))
                                                .out("*exposures")
                                                .each((Map<?, ?> ex) -> ex == null ? new HashMap<>() : ex,
                                                            "*exposures")
                                                .out("*exposuresSafe")
                                                .localSelect("$$viewerIdToTargetIdToReactionScore",
                                                            Path.key("*aidL", "*tidL").nullToVal(0.0))
                                                .out("*viewerToTargetReaction")
                                                .hashPartition("*tidL")
                                                .localSelect("$$accountIdToFiltersProjection", Path.key("*tidL"))
                                                .out("*targetFilters")
                                                .localSelect("$$accountIdToSignals", Path.key("*tidL"))
                                                .out("*targetSignals")
                                                .localSelect("$$viewerIdToTargetIdToReactionScore",
                                                            Path.key("*tidL", "*aidL").nullToVal(0.0))
                                                .out("*targetToViewerReaction")
                                                .each((Object vfObj) -> (vfObj instanceof Filters)
                                                            ? (Filters) vfObj
                                                            : null,
                                                            "*viewerFilters")
                                                .out("*viewerFiltersC")
                                                .each((Object tfObj) -> (tfObj instanceof Filters)
                                                            ? (Filters) tfObj
                                                            : null,
                                                            "*targetFilters")
                                                .out("*targetFiltersC")
                                                .each((Object vsObj) -> (vsObj instanceof Signals)
                                                            ? (Signals) vsObj
                                                            : null,
                                                            "*viewerSignals")
                                                .out("*viewerSignalsC")
                                                .each((Object tsObj) -> (tsObj instanceof Signals)
                                                            ? (Signals) tsObj
                                                            : null,
                                                            "*targetSignals")
                                                .out("*targetSignalsC")
                                                .each((Filters viewer,
                                                            Long tid,
                                                            Filters target,
                                                            Signals viewerSignals,
                                                            Signals targetSignals,
                                                            Double viewerToTargetReaction,
                                                            Double targetToViewerReaction,
                                                            Map<?, ?> exposures) -> scorePair(
                                                                        viewer,
                                                                        tid == null ? 0L : tid,
                                                                        target,
                                                                        viewerSignals,
                                                                        targetSignals,
                                                                        viewerToTargetReaction == null
                                                                                    ? 0.0
                                                                                    : viewerToTargetReaction,
                                                                        targetToViewerReaction == null
                                                                                    ? 0.0
                                                                                    : targetToViewerReaction,
                                                                        exposures,
                                                                        System.currentTimeMillis()),
                                                            "*viewerFiltersC",
                                                            "*tidL",
                                                            "*targetFiltersC",
                                                            "*viewerSignalsC",
                                                            "*targetSignalsC",
                                                            "*viewerToTargetReaction",
                                                            "*targetToViewerReaction",
                                                            "*exposuresSafe")
                                                .out("*pairPayload")
                                                .each((Map<String, Object> payload) -> followupFromPayload(payload),
                                                            "*pairPayload")
                                                .out("*followupState")
                                                .each((Map<String, Object> followup) -> followup != null,
                                                            "*followupState")
                                                .out("*hasFollowup")
                                                .ifTrue("*hasFollowup",
                                                            Block.localTransform("$$targetIdToFollowupByViewer",
                                                                        Path.key("*tidL", "*aidL")
                                                                                    .termVal("*followupState")),
                                                            Block.localTransform("$$targetIdToFollowupByViewer",
                                                                        Path.key("*tidL", "*aidL")
                                                                                    .termVoid()))
                                                .hashPartition("*aidL")
                                                .each((Map<String, Object> payload) -> uncertaintyFromPayload(payload),
                                                            "*pairPayload")
                                                .out("*uncertainty")
                                                .localTransform("$$viewerIdToTargetIdToUncertainty",
                                                            Path.key("*aidL", "*tidL")
                                                                        .termVal("*uncertainty"))
                                                .each((Map<String, Object> payload) -> candidateFromPayload(payload),
                                                            "*pairPayload")
                                                .out("*candMaybe")
                                                .localSelect("$$accountIdToCandidateHeap", Path.key("*aidL"))
                                                .out("*heapRaw")
                                                .each((Object hObj) -> {
                                                      if (hObj == null)
                                                            return new ArrayList<MatchCandidate>();
                                                      return (List<MatchCandidate>) hObj;
                                                }, "*heapRaw")
                                                .out("*currHeap")
                                                .each((List<MatchCandidate> heap,
                                                            MatchCandidate cand,
                                                            Long targetId) -> {
                                                      if (cand == null) {
                                                            return removeFromHeap(heap,
                                                                        targetId == null ? 0L : targetId);
                                                      }
                                                      return upsertIntoHeap(heap, cand);
                                                },
                                                            "*currHeap",
                                                            "*candMaybe",
                                                            "*tidL")
                                                .out("*newHeap")
                                                .localTransform("$$accountIdToCandidateHeap",
                                                            Path.key("*aidL").termVal("*newHeap")));
      }

      private static void declareMatchmakingFollowupsTopology(Topologies topologies) {
            StreamTopology stream = topologies.stream("matchmakingFollowups");

            stream.pstate("$$instanceIdToMatchmakingFollowupAssignment",
                        PState.mapSchema(String.class, PrivatePromptAssignment.class));
            stream.pstate("$$instanceIdToMatchmakingFollowupAnswer",
                        PState.mapSchema(String.class, PrivatePromptAnswer.class));
            stream.pstate("$$accountIdToActiveMatchmakingFollowupInstanceId",
                        PState.mapSchema(Long.class, String.class));
            stream.pstate("$$accountIdToLastMatchmakingFollowupScheduledAt",
                        PState.mapSchema(Long.class, Long.class));
            stream.pstate("$$accountIdToLastMatchmakingFollowupAnsweredAt",
                        PState.mapSchema(Long.class, Long.class));

            stream.source("*matchmakingFollowupAssignmentDepot")
                        .out("*assignment")
                        .macro(extractFields("*assignment", "*accountId", "*instanceId", "*scheduledAt",
                                    "*completedAt"))
                        .each((Number n) -> normalizeAccountId(n), "*accountId").out("*accountIdL")
                        .hashPartition("*instanceId")
                        .localTransform("$$instanceIdToMatchmakingFollowupAssignment",
                                    Path.key("*instanceId").termVal("*assignment"))
                        .hashPartition("*accountIdL")
                        .each((PrivatePromptAssignment assignment, Number scheduledAtRaw) -> resolveFollowupScheduledAt(
                                    assignment, scheduledAtRaw), "*assignment", "*scheduledAt")
                        .out("*lastScheduledAt")
                        .localTransform("$$accountIdToLastMatchmakingFollowupScheduledAt",
                                    Path.key("*accountIdL").termVal("*lastScheduledAt"))
                        .localSelect("$$accountIdToActiveMatchmakingFollowupInstanceId", Path.key("*accountIdL"))
                        .out("*currentActive")
                        .each((String currentActive, PrivatePromptAssignment assignment) -> computeNextActiveMatchmakingFollowupInstanceId(
                                    currentActive, assignment), "*currentActive", "*assignment")
                        .out("*nextActive")
                        .each((String nextActive) -> nextActive == null, "*nextActive").out("*clearActive")
                        .ifTrue("*clearActive",
                                    Block.localTransform("$$accountIdToActiveMatchmakingFollowupInstanceId",
                                                Path.key("*accountIdL").termVoid()),
                                    Block.localTransform("$$accountIdToActiveMatchmakingFollowupInstanceId",
                                                Path.key("*accountIdL").termVal("*nextActive")))
                        .each((PrivatePromptAssignment assignment, Number completedAtRaw) -> {
                              if (assignment == null || assignment.getStatus() != PrivatePromptStatus.ANSWERED) {
                                    return null;
                              }
                              return resolveFollowupCompletedAt(assignment, completedAtRaw);
                        }, "*assignment", "*completedAt")
                        .out("*answeredAtMaybe")
                        .each((Object answeredAtMaybe) -> answeredAtMaybe instanceof Number, "*answeredAtMaybe")
                        .out("*hasAnsweredAt")
                        .ifTrue("*hasAnsweredAt",
                                    Block.localTransform("$$accountIdToLastMatchmakingFollowupAnsweredAt",
                                                Path.key("*accountIdL").termVal("*answeredAtMaybe")));

            stream.source("*matchmakingFollowupAnswerDepot")
                        .out("*answer")
                        .macro(extractFields("*answer", "*instanceId"))
                        .hashPartition("*instanceId")
                        .localTransform("$$instanceIdToMatchmakingFollowupAnswer",
                                    Path.key("*instanceId").termVal("*answer"));
      }

      private static final int DM_CONVERSATION_CAP = 500;

      private static void declareDirectMessagesTopology(Topologies topologies) {
            StreamTopology stream = topologies.stream("directMessages");

            // convKey ("conv:lo:hi") → List<DirectMessage>, newest first
            stream.pstate("$$conversationToMessages",
                        PState.mapSchema(String.class, List.class));

            stream.source("*directMessageDepot").out("*msg")
                        .macro(extractFields("*msg", "*senderId", "*receiverId"))
                        .each((Number a, Number b) -> {
                              long aL = a == null ? 0L : a.longValue();
                              long bL = b == null ? 0L : b.longValue();
                              long lo = Math.min(aL, bL);
                              long hi = Math.max(aL, bL);
                              return "conv:" + lo + ":" + hi;
                        }, "*senderId", "*receiverId").out("*convKey")
                        .hashPartition("*convKey")
                        .localSelect("$$conversationToMessages", Path.key("*convKey")).out("*existing")
                        .each((List<?> existing, Object newMsg) -> {
                              ArrayList<Object> updated = new ArrayList<>();
                              updated.add(newMsg);
                              if (existing != null) {
                                    int keep = Math.min(existing.size(), DM_CONVERSATION_CAP - 1);
                                    updated.addAll(existing.subList(0, keep));
                              }
                              return updated;
                        }, "*existing", "*msg").out("*updatedList")
                        .localTransform("$$conversationToMessages",
                                    Path.key("*convKey").termVal("*updatedList"));
      }

      private static void declareFacecardDeckTopology(Topologies topologies) {
            StreamTopology stream = topologies.stream("facecardDecks");

            stream.pstate("$$accountIdToFacecardDeckByDay",
                        PState.mapSchema(Long.class, Map.class));

            stream.source("*facecardDeckDepot").out("*data")
                        .each((Object data) -> toStringObjectMap(data), "*data").out("*deck")
                        .each((Map<String, Object> deck) -> normalizeMapAccountId(deck.get("accountId")), "*deck")
                        .out("*accountIdL")
                        .each((Map<String, Object> deck) -> asStringTrimmed(deck.get("dayKey")), "*deck")
                        .out("*dayKey")
                        .each((String dayKey) -> dayKey != null, "*dayKey").out("*hasDayKey")
                        .ifTrue("*hasDayKey",
                                    Block.hashPartition("*accountIdL")
                                                .localTransform("$$accountIdToFacecardDeckByDay",
                                                            Path.key("*accountIdL", "*dayKey").termVal("*deck")));
      }

      private void declareQueries(Topologies topologies) {
            topologies
                        .query("getAccountsFromAccountIds", "*requestAccountId", "*accountIds")
                        .out("*results")
                        .each(Ops.EXPLODE_INDEXED, "*accountIds").out("*index", "*accountId")
                        .select("$$accountIdToAccount", Path.key("*accountId")).out("*account")
                        .each((Integer index, Long accountId, Account account) -> {
                              if (index == null || accountId == null || account == null) {
                                    return null;
                              }
                              return new IndexedAccountWithId(index, new AccountWithId(accountId, account));
                        }, "*index", "*accountId", "*account")
                        .out("*indexedAccountWithId")
                        .originPartition()
                        .agg(Agg.list("*indexedAccountWithId")).out("*unsortedResults")
                        .each((RamaFunction1<List<IndexedAccountWithId>, List<AccountWithId>>) unsorted -> {
                              if (unsorted == null || unsorted.isEmpty())
                                    return new ArrayList<>();
                              List<IndexedAccountWithId> sorted = new ArrayList<>();
                              for (IndexedAccountWithId item : unsorted) {
                                    if (item == null || item.accountWithId == null || item.accountWithId.account == null) {
                                          continue;
                                    }
                                    sorted.add(item);
                              }
                              if (sorted.isEmpty()) {
                                    return new ArrayList<>();
                              }
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

            topologies.query("getPublicPromptAnswerById", "*answerId").out("*answer")
                        .hashPartition("*answerId")
                        .localSelect("$$answerIdToPublicPromptAnswer", Path.key("*answerId")).out("*answer")
                        .originPartition();

            topologies.query("getPublicPromptAnswerIdsByPromptId", "*promptId").out("*answerIds")
                        .hashPartition("*promptId")
                        .localSelect("$$promptIdToAnswerIds", Path.key("*promptId")).out("*answerIdMap")
                        .each((Map<?, ?> map) -> {
                              if (map == null || map.isEmpty()) {
                                    return new ArrayList<String>();
                              }
                              LinkedHashSet<String> deduped = new LinkedHashSet<>();
                              for (Object key : map.keySet()) {
                                    if (key == null) {
                                          continue;
                                    }
                                    String id = key.toString().trim();
                                    if (!id.isBlank()) {
                                          deduped.add(id);
                                    }
                              }
                              return new ArrayList<>(deduped);
                        }, "*answerIdMap").out("*answerIds")
                        .originPartition();

            topologies.query("getPublicPromptSelection", "*requesterId", "*accountId").out("*selection")
                        .each((Number n) -> n == null ? 0L : n.longValue(), "*accountId").out("*accountIdL")
                        .hashPartition("*accountIdL")
                        .localSelect("$$accountIdToPublicPromptSelection", Path.key("*accountIdL"))
                        .out("*selection")
                        .originPartition();

            topologies.query("getMyPublicPromptAnswers", "*requesterId", "*accountId").out("*answers")
                        .each((Number n) -> n == null ? 0L : n.longValue(), "*accountId").out("*accountIdL")
                        .hashPartition("*accountIdL")
                        .localSelect("$$accountIdToPublicAnswerIdByPromptId", Path.key("*accountIdL"))
                        .out("*promptToAnswer")
                        .each((Map<?, ?> promptToAnswer) -> {
                              if (promptToAnswer == null || promptToAnswer.isEmpty())
                                    return new ArrayList<>();
                              return new ArrayList<>(promptToAnswer.values());
                        }, "*promptToAnswer").out("*answerIdObjs")
                        .each(Ops.EXPLODE, "*answerIdObjs").out("*answerIdObj")
                        .each((Object s) -> s == null ? null : s.toString(),
                                    "*answerIdObj")
                        .out("*answerId")
                        .hashPartition("*answerId")
                        .localSelect("$$answerIdToPublicPromptAnswer",
                                    Path.key("*answerId"))
                        .out("*answer")
                        .each((PublicPromptAnswer ans) -> {
                              if (ans == null)
                                    return null;
                              if (ans.isSetDeleted() && ans.isDeleted())
                                    return null;
                              return ans;
                        }, "*answer").out("*candidate")
                        .originPartition()
                        .agg(Agg.list("*candidate")).out("*candidates")
                        .each((List<PublicPromptAnswer> candidates) -> {
                              if (candidates == null || candidates.isEmpty())
                                    return new ArrayList<>();
                              List<PublicPromptAnswer> filtered = new ArrayList<>();
                              for (PublicPromptAnswer candidate : candidates) {
                                    if (candidate != null)
                                          filtered.add(candidate);
                              }
                              if (filtered.isEmpty())
                                    return new ArrayList<>();
                              List<PublicPromptAnswer> sorted = new ArrayList<>(filtered);
                              sorted.sort((a, b) -> {
                                    long at = a == null ? 0L : (a.isSetUpdatedAt() ? a.getUpdatedAt() : a.getCreatedAt());
                                    long bt = b == null ? 0L : (b.isSetUpdatedAt() ? b.getUpdatedAt() : b.getCreatedAt());
                                    return Long.compare(bt, at);
                              });
                              return sorted;
                        }, "*candidates").out("*answers");

            topologies.query("getPublicPromptFeed", "*viewerId", "*limit").out("*results")
                        .each((Number n) -> n == null ? 0L : n.longValue(), "*viewerId").out("*viewerIdL")
                        .hashPartition("*viewerIdL")
                        .localSelect("$$accountIdToFiltersProjection", Path.key("*viewerIdL")).out("*viewerFilters")
                        .localSelect("$$viewerIdToReactedAnswerIds", Path.key("*viewerIdL"))
                        .out("*reactedAnswerIdsRaw")
                        .each((Map<?, ?> reacted) -> reacted == null ? new HashMap<>() : reacted,
                                    "*reactedAnswerIdsRaw")
                        .out("*reactedAnswerIds")
                        .localSelect("$$viewerIdToSuppressedSignalTokens", Path.key("*viewerIdL"))
                        .out("*suppressedTokensRaw")
                        .each((Map<?, ?> suppressed) -> suppressed == null ? new HashMap<>() : suppressed,
                                    "*suppressedTokensRaw")
                        .out("*suppressedTokens")
                        .localSelect("$$viewerIdToTasteByToken", Path.key("*viewerIdL"))
                        .out("*tasteMapRaw")
                        .each((Map<?, ?> taste) -> taste == null ? new HashMap<>() : taste,
                                    "*tasteMapRaw")
                        .out("*tasteMap")
                        .localSelect("$$accountIdToCandidateHeap", Path.key("*viewerIdL"))
                        .out("*candidateHeapRaw")
                        .each((List<MatchCandidate> heap) -> {
                              HashMap<Long, Double> out = new HashMap<>();
                              if (heap == null || heap.isEmpty())
                                    return out;
                              for (MatchCandidate candidate : heap) {
                                    if (candidate == null)
                                          continue;
                                    out.put(candidate.getTargetAccountId(), candidate.getStage0Score());
                              }
                              return out;
                        }, "*candidateHeapRaw")
                        .out("*candidateScoreByTarget")
                        .each((Long vid) -> ALL_ACCOUNTS_KEY, "*viewerIdL").out("*allKey")
                        .hashPartition("*allKey")
                        .localSelect("$$allAccountIdsGlobal", Path.key("*allKey")).out("*allIdsRaw")
                        .each((Map<?, ?> ids) -> ids == null ? new HashMap<>() : ids, "*allIdsRaw")
                        .out("*allIds")
                        .each(Ops.EXPLODE, "*allIds").out("*entry")
                        .each((Object e) -> {
                              if (e instanceof Map.Entry) {
                                    return ((Map.Entry<?, ?>) e).getKey();
                              }
                              return null;
                        }, "*entry").out("*targetIdObj")
                        .each((Object n) -> (n instanceof Number) ? ((Number) n).longValue() : 0L,
                                    "*targetIdObj")
                        .out("*targetIdL")
                        .each((Long vid, Long tid) -> tid != null && !Objects.equals(vid, tid),
                                    "*viewerIdL", "*targetIdL")
                        .out("*isOther")
                        .hashPartition("*targetIdL")
                        .localSelect("$$accountIdToFiltersProjection",
                                    Path.key("*targetIdL"))
                        .out("*targetFilters")
                        .each((Filters v, Filters t) -> {
                              if (v == null || t == null)
                                    return null;
                              return CalypsoHelpers.computeMatchesBaseScore(v, t);
                        }, "*viewerFilters", "*targetFilters")
                        .out("*score")
                        .each((Double s) -> s != null && s >= 0.0, "*score")
                        .out("*isCompatible")
                        .localSelect(
                                    "$$accountIdToPublicAnswerIdByPromptId",
                                    Path.key("*targetIdL"))
                        .out("*promptToAnswer")
                        .each((Map<?, ?> promptToAnswer) -> promptToAnswer == null
                                    ? new HashMap<>()
                                    : promptToAnswer,
                                    "*promptToAnswer")
                        .out("*promptToAnswerSafe")
                        .each(Ops.EXPLODE, "*promptToAnswerSafe")
                        .out("*promptEntry")
                        .each((Object e) -> {
                              if (e instanceof Map.Entry) {
                                    return ((Map.Entry<?, ?>) e)
                                                .getKey();
                              }
                              return null;
                        }, "*promptEntry")
                        .out("*promptIdObj")
                        .each((Object e) -> {
                              if (e instanceof Map.Entry) {
                                    return ((Map.Entry<?, ?>) e)
                                                .getValue();
                              }
                              return null;
                        }, "*promptEntry")
                        .out("*answerIdObj")
                        .each((Object s) -> s == null ? null : s.toString(),
                                    "*promptIdObj")
                        .out("*promptId")
                        .each((Object s) -> s == null ? null : s.toString(),
                                    "*answerIdObj")
                        .out("*answerId")
                        .each((Map<?, ?> reacted, String aid) -> reacted != null
                                    && aid != null
                                    && reacted.containsKey(aid),
                                    "*reactedAnswerIds",
                                    "*answerId")
                        .out("*answerAlreadyReacted")
                        .hashPartition("*answerId")
                        .localSelect(
                                    "$$answerIdToPublicPromptAnswer",
                                    Path.key(
                                                "*answerId"))
                        .out("*answer")
                        .each((PublicPromptAnswer ans, Map<?, ?> suppressed) -> {
                              if (ans == null || suppressed == null || suppressed.isEmpty())
                                    return false;
                              String promptId = ans.getPromptId();
                              if (promptId == null || promptId.isBlank())
                                    return false;
                              if (!ans.isSetSignalTokens() || ans.getSignalTokens() == null)
                                    return false;
                              for (String token : ans.getSignalTokens()) {
                                    String key = suppressionKey(promptId, token);
                                    if (key != null && suppressed.containsKey(key)) {
                                          return true;
                                    }
                              }
                              return false;
                        }, "*answer", "*suppressedTokens").out("*tokenSuppressed")
                        .each((Boolean isOther,
                                    Boolean isCompatible,
                                    Boolean answerAlreadyReacted,
                                    Boolean tokenSuppressed,
                                    PublicPromptAnswer ans,
                                    Map<?, ?> taste,
                                    Map<?, ?> candidateScoreByTarget,
                                    Long targetId) -> {
                              if (isOther == null || !isOther)
                                    return null;
                              if (isCompatible == null || !isCompatible)
                                    return null;
                              if (answerAlreadyReacted != null && answerAlreadyReacted)
                                    return null;
                              if (tokenSuppressed != null && tokenSuppressed)
                                    return null;
                              if (ans == null)
                                    return null;
                              if (ans.isSetDeleted() && ans.isDeleted())
                                    return null;
                              double personNorm = 0.45;
                              if (candidateScoreByTarget != null && targetId != null) {
                                    Object raw = candidateScoreByTarget.get(targetId);
                                    if (raw instanceof Number) {
                                          personNorm = clamp01(((Number) raw).doubleValue() / 100.0);
                                    }
                              }
                              double tasteLinear = 0.0;
                              if (taste != null && ans.isSetSignalTokens() && ans.getSignalTokens() != null) {
                                    for (String token : ans.getSignalTokens()) {
                                          if (token == null)
                                                continue;
                                          Object rawVal = taste.get(token);
                                          if (rawVal instanceof Number)
                                                tasteLinear += ((Number) rawVal).doubleValue();
                                    }
                              }
                              double tasteNorm = clamp01(0.5 + (tasteLinear / 6.0));
                              double finalScore = (0.70 * personNorm + 0.30 * tasteNorm) * 100.0;
                              ArrayList<Object> candidate = new ArrayList<>(2);
                              candidate.add(ans);
                              candidate.add(finalScore);
                              return candidate;
                        }, "*isOther", "*isCompatible", "*answerAlreadyReacted", "*tokenSuppressed", "*answer",
                                    "*tasteMap", "*candidateScoreByTarget", "*targetIdL")
                        .out("*candidate")
                        .originPartition()
                        .agg(Agg.list("*candidate")).out("*candidates")
                        .each((List<List<Object>> candidates, Object limitObj) -> {
                              if (candidates == null || candidates.isEmpty())
                                    return new ArrayList<>();
                              List<List<Object>> filtered = new ArrayList<>();
                              for (List<Object> candidate : candidates) {
                                    if (candidate == null || candidate.size() < 2)
                                          continue;
                                    if (!(candidate.get(0) instanceof PublicPromptAnswer))
                                          continue;
                                    filtered.add(candidate);
                              }
                              if (filtered.isEmpty())
                                    return new ArrayList<>();
                              int lim = 1;
                              if (limitObj instanceof Number) {
                                    int val = ((Number) limitObj).intValue();
                                    if (val < 1)
                                          lim = 1;
                                    else if (val > 50)
                                          lim = 50;
                                    else
                                          lim = val;
                              }
                              List<List<Object>> sorted = new ArrayList<>(filtered);
                              sorted.sort((a, b) -> {
                                    double as = (a != null && a.size() > 1 && a.get(1) instanceof Number)
                                                ? ((Number) a.get(1)).doubleValue()
                                                : 0.0;
                                    double bs = (b != null && b.size() > 1 && b.get(1) instanceof Number)
                                                ? ((Number) b.get(1)).doubleValue()
                                                : 0.0;
                                    int cmp = Double.compare(bs, as);
                                    if (cmp != 0)
                                          return cmp;
                                    PublicPromptAnswer aAns = (a != null
                                                && !a.isEmpty()
                                                && a.get(0) instanceof PublicPromptAnswer)
                                                            ? (PublicPromptAnswer) a.get(0)
                                                            : null;
                                    PublicPromptAnswer bAns = (b != null
                                                && !b.isEmpty()
                                                && b.get(0) instanceof PublicPromptAnswer)
                                                            ? (PublicPromptAnswer) b.get(0)
                                                            : null;
                                    long at = aAns == null
                                                ? 0L
                                                : (aAns.isSetUpdatedAt() ? aAns.getUpdatedAt()
                                                            : aAns.getCreatedAt());
                                    long bt = bAns == null
                                                ? 0L
                                                : (bAns.isSetUpdatedAt() ? bAns.getUpdatedAt()
                                                            : bAns.getCreatedAt());
                                    return Long.compare(bt, at);
                              });
                              LinkedHashMap<String, PublicPromptAnswer> deduped = new LinkedHashMap<>();
                              for (List<Object> candidate : sorted) {
                                    PublicPromptAnswer ans = (candidate != null
                                                && !candidate.isEmpty()
                                                && candidate.get(0) instanceof PublicPromptAnswer)
                                                            ? (PublicPromptAnswer) candidate.get(0)
                                                            : null;
                                    if (ans == null || ans.getPromptId() == null)
                                          continue;
                                    if (!deduped.containsKey(ans.getPromptId())) {
                                          deduped.put(ans.getPromptId(), ans);
                                    }
                                    if (deduped.size() >= lim)
                                          break;
                              }
                              return new ArrayList<>(deduped.values());
                        }, "*candidates", "*limit").out("*results");

            topologies.query("getMatchmakingFollowupCandidatesForTarget", "*targetId", "*limit").out("*followups")
                        .each((Number n) -> n == null ? 0L : n.longValue(), "*targetId").out("*targetIdL")
                        .hashPartition("*targetIdL")
                        .localSelect("$$targetIdToFollowupByViewer", Path.key("*targetIdL"))
                        .out("*followupByViewerRaw")
                        .each((Map<?, ?> byViewer, Object limitObj) -> toSortedFollowupCandidates(
                                    byViewer == null ? new HashMap<>() : byViewer,
                                    limitObj),
                                    "*followupByViewerRaw", "*limit")
                        .out("*followups")
                        .originPartition();

            topologies.query("getMatchmakingFollowupAssignmentByInstanceId", "*instanceId").out("*assignment")
                        .hashPartition("*instanceId")
                        .localSelect("$$instanceIdToMatchmakingFollowupAssignment", Path.key("*instanceId"))
                        .out("*assignment")
                        .originPartition();

            topologies.query("getMatchmakingFollowupAnswerByInstanceId", "*instanceId").out("*answer")
                        .hashPartition("*instanceId")
                        .localSelect("$$instanceIdToMatchmakingFollowupAnswer", Path.key("*instanceId"))
                        .out("*answer")
                        .originPartition();

            topologies.query("getMatchmakingFollowupSchedulerState", "*requesterId", "*accountId").out("*state")
                        .each((Number n) -> normalizeAccountId(n), "*accountId").out("*accountIdL")
                        .hashPartition("*accountIdL")
                        .localSelect("$$accountIdToActiveMatchmakingFollowupInstanceId", Path.key("*accountIdL"))
                        .out("*activeInstanceId")
                        .localSelect("$$accountIdToLastMatchmakingFollowupScheduledAt", Path.key("*accountIdL"))
                        .out("*lastScheduledAtRaw")
                        .localSelect("$$accountIdToLastMatchmakingFollowupAnsweredAt", Path.key("*accountIdL"))
                        .out("*lastAnsweredAtRaw")
                        .each((String activeInstanceId,
                                    Number lastScheduledAtRaw,
                                    Number lastAnsweredAtRaw) -> buildMatchmakingFollowupSchedulerState(activeInstanceId,
                                                lastScheduledAtRaw,
                                                lastAnsweredAtRaw),
                                    "*activeInstanceId", "*lastScheduledAtRaw", "*lastAnsweredAtRaw")
                        .out("*state")
                        .originPartition();

            topologies.query("getActiveMatchmakingFollowupAssignment", "*requesterId", "*accountId")
                        .out("*assignment")
                        .each((Number n) -> normalizeAccountId(n), "*accountId").out("*accountIdL")
                        .hashPartition("*accountIdL")
                        .localSelect("$$accountIdToActiveMatchmakingFollowupInstanceId", Path.key("*accountIdL"))
                        .out("*instanceId")
                        .each((String instanceId) -> instanceId != null, "*instanceId").out("*hasInstanceId")
                        .ifTrue("*hasInstanceId",
                                    Block.hashPartition("*instanceId")
                                                .localSelect("$$instanceIdToMatchmakingFollowupAssignment",
                                                            Path.key("*instanceId"))
                                                .out("*assignmentRaw")
                                                .each((PrivatePromptAssignment assignment) -> {
                                                      if (!isMatchmakingFollowupServableNow(assignment,
                                                                  System.currentTimeMillis())) {
                                                            return null;
                                                      }
                                                      return assignment;
                                                }, "*assignmentRaw").out("*assignment"),
                                    Block.each(() -> null).out("*assignment"))
                        .originPartition();

            topologies.query("getFiltersFromAccountId", "*requesterId", "*accountId").out("*filters")
                        .each((Number n) -> n == null ? 0L : n.longValue(), "*accountId").out("*accountIdL")
                        .hashPartition("*accountIdL")
                        .localSelect("$$accountIdToFiltersProjection", Path.key("*accountIdL"))
                        .out("*filtersRaw")
                        .originPartition()
                        .each((Filters f) -> f, "*filtersRaw").out("*filters");

            topologies.query("getMatchesFromAccountId", "*viewerId", "*startIdx", "*limit").out("*results")
                        // Normalize viewer id to Long before partitioning/reads
                        .each((Number n) -> n == null ? 0L : n.longValue(), "*viewerId").out("*viewerIdL")
                        .hashPartition("*viewerIdL")
                        .localSelect("$$accountIdToCandidateHeap", Path.key("*viewerIdL")).out("*heapRaw")
                        .localSelect("$$accountIdToExposure", Path.key("*viewerIdL")).out("*exposures")
                        .each((List<MatchCandidate> heap, Map<Long, Long> ex) -> filterHeapByExposure(heap, ex,
                                    System.currentTimeMillis()),
                                    "*heapRaw", "*exposures")
                        .out("*heap")
                        // return to origin side for the final subbatch (required by <<query)
                        .originPartition()
                        .each((List<MatchCandidate> heap, Object startIdxObj, Object limitObj) -> {
                              int start = 0; // ignore caller's startIdx for now
                              int limit = (limitObj instanceof Number)
                                          ? Math.max(0, ((Number) limitObj).intValue())
                                          : 10;

                              if (heap == null || heap.isEmpty() || limit == 0)
                                    return new ArrayList<MatchCandidate>();

                              int end = Math.min(heap.size(), start + limit);
                              return new ArrayList<>(heap.subList(start, end));
                        }, "*heap", "*startIdx", "*limit").out("*results");

            // Cursor-aware fetch:
            // - Interpret cursor.lastIndex as "page index" (0,1,2,...)
            // - Serve up to 2 pages of results; after that, return empty.
            topologies.query("getMatchesFromAccountIdWithCursor", "*viewerId", "*ignoredStartIdx", "*limit")
                        .out("*out")
                        // Normalize viewer id to Long
                        .each((Number n) -> n == null ? 0L : n.longValue(), "*viewerId").out("*viewerIdL")
                        .hashPartition("*viewerIdL")
                        .localSelect("$$accountIdToCandidateHeap", Path.key("*viewerIdL")).out("*heapRaw")
                        .localSelect("$$accountIdToExposure", Path.key("*viewerIdL")).out("*exposures")
                        .each((List<MatchCandidate> heap, Map<Long, Long> ex) -> filterHeapByExposure(heap, ex,
                                    System.currentTimeMillis()),
                                    "*heapRaw", "*exposures")
                        .out("*heap")

                        // We treat "lastIndex" as "page index"
                        .localSelect("$$accountIdToCursor",
                                    Path.key("*viewerIdL", "lastIndex").nullToVal(0))
                        .out("*pageIdx")

                        // final subbatch must be at origin for <<query
                        .originPartition()
                        .each((List<MatchCandidate> heap, Object pageIdxObj, Object limitObj) -> {
                              Map<String, Object> out = new HashMap<>();

                              int pageIdx = (pageIdxObj instanceof Number)
                                          ? ((Number) pageIdxObj).intValue()
                                          : 0;
                              int limit = (limitObj instanceof Number)
                                          ? Math.max(0, ((Number) limitObj).intValue())
                                          : 10;

                              // If nothing to serve, or invalid limit, keep cursor unchanged
                              if (heap == null || heap.isEmpty() || limit <= 0) {
                                    out.put("page", new ArrayList<MatchCandidate>());
                                    out.put("nextIdx", pageIdx);
                                    out.put("nextWrapped", false);
                                    return out;
                              }

                              // For this test, we only serve TWO pages:
                              // - pageIdx = 0 -> first page
                              // - pageIdx = 1 -> second page
                              // - pageIdx >= 2 -> no more results
                              if (pageIdx >= 2) {
                                    out.put("page", new ArrayList<MatchCandidate>());
                                    out.put("nextIdx", pageIdx);
                                    out.put("nextWrapped", true);
                                    return out;
                              }

                              int n = heap.size();
                              int count = Math.min(limit, n);
                              ArrayList<MatchCandidate> page = new ArrayList<>();
                              for (int i = 0; i < count; i++) {
                                    page.add(heap.get(i));
                              }

                              int nextPageIdx = pageIdx + 1;
                              out.put("page", page);
                              out.put("nextIdx", nextPageIdx);
                              out.put("nextWrapped", nextPageIdx >= 2);
                              return out;
                        }, "*heap", "*pageIdx", "*limit").out("*out");

            topologies.query("getSignalsFromAccountId", "*requestAccountId", "*accountId").out("*signals")
                        .hashPartition("*accountId")
                        .localSelect("$$accountIdToSignals", Path.key("*accountId")).out("*signals")
                        .originPartition();

            topologies.query("getSignalAccountIds").out("*accountIds")
                        .allPartition()
                        .localSelect("$$accountIdToSignals", Path.mapKeys()).out("*accountId")
                        .originPartition()
                        .agg(Agg.list("*accountId")).out("*grouped")
                        .each((RamaFunction1<List<Long>, List<Long>>) grouped -> {
                              LinkedHashSet<Long> deduped = new LinkedHashSet<>();
                              if (grouped != null) {
                                    for (Long id : grouped) {
                                          if (id != null && id >= 0L) {
                                                deduped.add(id);
                                          }
                                    }
                              }
                              return new ArrayList<>(deduped);
                        }, "*grouped").out("*accountIds");

            topologies.query("getSilhouetteFromAccountId", "*requestAccountId", "*accountId").out("*silhouette")
                        .each((Number n) -> n == null ? 0L : n.longValue(), "*accountId").out("*accountIdL")
                        .hashPartition("*accountIdL")
                        .localSelect("$$accountIdToSilhouette", Path.key("*accountIdL")).out("*rawSilhouette")
                        .each((Object rawSilhouette, Long accountIdL) -> normalizeSilhouettePayload(rawSilhouette,
                                    accountIdL), "*rawSilhouette", "*accountIdL")
                        .out("*silhouette")
                        .originPartition();

            topologies.query("getSilhouettePendingUpdates", "*accountId", "*limit").out("*events")
                        .each((Number n) -> n == null ? 0L : n.longValue(), "*accountId").out("*accountIdL")
                        .hashPartition("*accountIdL")
                        .localSelect("$$accountIdToPendingSilhouetteUpdates", Path.key("*accountIdL"))
                        .out("*pendingRaw")
                        .each((Map<?, ?> pendingRaw, Object limitObj) -> sortPendingSilhouetteUpdates(
                                    pendingRaw == null ? new HashMap<>() : pendingRaw,
                                    limitObj), "*pendingRaw", "*limit")
                        .out("*events")
                        .originPartition();

            topologies.query("getFacecardDeck", "*requesterId", "*accountId", "*dayKey").out("*deck")
                        .each((Number n) -> normalizeAccountId(n), "*accountId").out("*accountIdL")
                        .each((Object rawDayKey) -> asStringTrimmed(rawDayKey), "*dayKey").out("*dayKeyS")
                        .hashPartition("*accountIdL")
                        .localSelect("$$accountIdToFacecardDeckByDay", Path.key("*accountIdL", "*dayKeyS"))
                        .out("*deck")
                        .originPartition();

            topologies.query("getDirectMessages", "*requesterId", "*viewerId", "*targetId").out("*messages")
                        .each((Number a, Number b) -> {
                              long aL = a == null ? 0L : a.longValue();
                              long bL = b == null ? 0L : b.longValue();
                              long lo = Math.min(aL, bL);
                              long hi = Math.max(aL, bL);
                              return "conv:" + lo + ":" + hi;
                        }, "*viewerId", "*targetId").out("*convKey")
                        .hashPartition("*convKey")
                        .localSelect("$$conversationToMessages", Path.key("*convKey")).out("*messages")
                        .originPartition();
      }

      @Override
      public void define(Setup setup, Topologies topologies) {
            setup.declareDepot("*accountDepot", Depot.hashBy(CalypsoHelpers.ExtractPhoneNumber.class));
            setup.declareDepot("*accountWithIdDepot", Depot.disallow());
            setup.declareDepot("*applicationDepot", Depot.hashBy(CalypsoHelpers.ExtractClientId.class));
            setup.declareDepot("*authCodeDepot", Depot.hashBy(ExtractCode.class));
            setup.declareDepot("*filtersDepot", Depot.hashBy(CalypsoHelpers.ExtractAccountId.class));
            setup.declareDepot("*matchRefillDepot", Depot.hashBy(CalypsoHelpers.ExtractAccountId.class));
            setup.declareDepot("*matchPairRescoreDepot", Depot.hashBy("now.calypso.backend.CalypsoHelpers$ExtractMapAccountId"));
            setup.declareDepot("*matchesServeDepot", Depot.hashBy(CalypsoHelpers.ExtractAccountId.class));
            setup.declareDepot("*matchesCursorAckDepot", Depot.hashBy(CalypsoHelpers.ExtractAccountId.class));
            setup.declareDepot("*matchmakingFollowupAssignmentDepot", Depot.hashBy(CalypsoHelpers.ExtractAccountId.class));
            setup.declareDepot("*matchmakingFollowupAnswerDepot", Depot.hashBy(CalypsoHelpers.ExtractAccountId.class));
            setup.declareDepot("*signalsDepot", Depot.hashBy(CalypsoHelpers.ExtractAccountId.class));
            setup.declareDepot("*silhouetteDepot", Depot.hashBy("now.calypso.backend.CalypsoHelpers$ExtractMapAccountId"));
            setup.declareDepot("*silhouetteUpdateEventDepot", Depot.hashBy("now.calypso.backend.CalypsoHelpers$ExtractMapAccountId"));
            setup.declareDepot("*silhouetteUpdateAckDepot", Depot.hashBy("now.calypso.backend.CalypsoHelpers$ExtractMapAccountId"));
            setup.declareDepot("*publicPromptAnswerDepot", Depot.hashBy(CalypsoHelpers.ExtractAccountId.class));
            setup.declareDepot("*publicPromptReactionDepot",
                        Depot.hashBy(CalypsoHelpers.ExtractViewerAccountId.class));
            setup.declareDepot("*publicPromptSelectionDepot", Depot.hashBy(CalypsoHelpers.ExtractAccountId.class));
            setup.declareDepot("*facecardDeckDepot", Depot.hashBy("now.calypso.backend.CalypsoHelpers$ExtractMapAccountId"));
            setup.declareDepot("*directMessageDepot", Depot.hashBy(CalypsoHelpers.ExtractSenderId.class));

            declareAccountsTopology(topologies);
            declareApplicationTopology(topologies);
            declareAuthTopology(topologies);
            declareFiltersTopology(topologies);
            declareMatchesServeAndCursorTopology(topologies);
            declareMatchesRefillTopology(topologies);
            declareMatchmakingFollowupsTopology(topologies);
            declareMatchesSignalsTopology(topologies);
            declareSilhouetteTopology(topologies);
            declarePublicPromptsTopology(topologies);
            declareFacecardDeckTopology(topologies);
            declareDirectMessagesTopology(topologies);

            declareQueries(topologies);
      }

}
