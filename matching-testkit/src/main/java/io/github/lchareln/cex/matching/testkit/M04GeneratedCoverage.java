package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.reference.LinearReferenceModel;
import io.github.lchareln.cex.matching.reference.ReferenceCommand;
import io.github.lchareln.cex.matching.reference.SemanticBook;
import io.github.lchareln.cex.matching.reference.SemanticEvent;
import io.github.lchareln.cex.matching.reference.SemanticOutcome;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Semantic pre-state coverage measured from the actual generated histories. */
final class M04GeneratedCoverage {
  static final String IOC_ZERO_FILL = "IOC_ZERO_FILL";
  static final String IOC_PARTIAL_FILL = "IOC_PARTIAL_FILL";
  static final String IOC_FULL_FILL = "IOC_FULL_FILL";
  static final String FOK_INSUFFICIENT = "FOK_INSUFFICIENT";
  static final String FOK_ACCEPTED = "FOK_ACCEPTED";
  static final String FOK_EXACT = "FOK_EXACT";
  static final String FOK_MULTI_LEVEL = "FOK_MULTI_LEVEL";
  static final String FOK_OUTSIDE_LIMIT_EXCLUDED = "FOK_OUTSIDE_LIMIT_EXCLUDED";
  static final String POST_ONLY_EMPTY_BOOK = "POST_ONLY_EMPTY_BOOK";
  static final String POST_ONLY_NON_CROSSING = "POST_ONLY_NON_CROSSING";
  static final String POST_ONLY_TOUCH = "POST_ONLY_TOUCH";
  static final String POST_ONLY_CROSS = "POST_ONLY_CROSS";
  static final String BASE_VALID_UNKNOWN = "BASE_VALID_UNKNOWN";
  static final String BASE_VALID_UNUSED_ID_UNKNOWN = "BASE_VALID_UNUSED_ID_UNKNOWN";
  static final String REJECTED_ID_LATER_REUSED = "REJECTED_ID_LATER_REUSED";
  private static final List<String> POLICIES = List.of("GTC", "IOC", "FOK", "POST_ONLY");
  private static final List<String> SIDES = List.of("BUY", "SELL");
  private static final BigInteger MAXIMUM = BigInteger.valueOf(Long.MAX_VALUE);

  Result analyze(M04GeneratorProfile profile, List<M04GeneratedHistory> histories) {
    profile.coverageRequirements().validate();
    if (histories.size() != profile.histories()) {
      throw new IllegalArgumentException("M04 coverage histories differ from frozen profile");
    }
    LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
    requiredKeys().forEach(key -> counts.put(key, 0));
    for (String side : SIDES) {
      for (String policy : POLICIES) {
        counts.put(sidePolicy(side, policy), 0);
      }
    }
    LinkedHashMap<String, Witness> first = new LinkedHashMap<>();
    Set<Occurrence> occurrences = new LinkedHashSet<>();
    for (M04GeneratedHistory history : List.copyOf(histories)) {
      LinearReferenceModel model = new LinearReferenceModel();
      Set<BigInteger> knownIds = new HashSet<>();
      Set<BigInteger> policyRejectedIds = new HashSet<>();
      for (int commandIndex = 0; commandIndex < history.commands().size(); commandIndex++) {
        ReferenceCommand command = history.commands().get(commandIndex);
        SemanticBook before = model.snapshot();
        Set<String> keys = new LinkedHashSet<>();
        if (command instanceof ReferenceCommand.Place place) {
          boolean baseValid = baseValid(place);
          boolean unused = baseValid && !knownIds.contains(place.orderId());
          if (unused
              && SIDES.contains(place.side())
              && POLICIES.contains(place.executionPolicy())) {
            keys.add(sidePolicy(place.side(), place.executionPolicy()));
          }
          if (baseValid && !POLICIES.contains(place.executionPolicy())) {
            keys.add(BASE_VALID_UNKNOWN);
            if (unused) {
              keys.add(BASE_VALID_UNUSED_ID_UNKNOWN);
            }
          }
          SemanticOutcome outcome = model.apply(command);
          classifyPolicy(place, before, outcome, baseValid, unused, keys);
          for (SemanticEvent event : outcome.events()) {
            if (event instanceof SemanticEvent.Rejected rejected
                && "INVALID_EXECUTION_POLICY".equals(rejected.code())
                && baseValid
                && unused) {
              policyRejectedIds.add(place.orderId());
            } else if (event instanceof SemanticEvent.PlaceRejected rejected
                && ("FOK_NOT_FILLABLE".equals(rejected.code())
                    || "POST_ONLY_WOULD_TAKE".equals(rejected.code()))) {
              policyRejectedIds.add(rejected.orderId());
            } else if (event instanceof SemanticEvent.Accepted accepted) {
              knownIds.add(accepted.orderId());
              if (policyRejectedIds.remove(accepted.orderId())) {
                keys.add(REJECTED_ID_LATER_REUSED);
              }
            }
          }
        } else {
          model.apply(command);
        }
        for (String key : keys) {
          Witness witness =
              new Witness(
                  history.historyIndex(), commandIndex, history.seedHex(), history.laneId());
          counts.put(key, counts.get(key) + 1);
          first.putIfAbsent(key, witness);
          occurrences.add(new Occurrence(key, history.historyIndex(), commandIndex));
        }
      }
    }
    Result result = new Result(counts, first, occurrences);
    result.assertRequired();
    return result;
  }

  private static void classifyPolicy(
      ReferenceCommand.Place place,
      SemanticBook before,
      SemanticOutcome outcome,
      boolean baseValid,
      boolean unused,
      Set<String> keys) {
    if (!baseValid || !unused) {
      return;
    }
    if ("IOC".equals(place.executionPolicy()) && accepted(outcome)) {
      BigInteger filled = traded(outcome);
      boolean remainder = hasRemainder(outcome);
      if (filled.signum() == 0 && remainder) {
        keys.add(IOC_ZERO_FILL);
      } else if (filled.signum() > 0 && remainder) {
        keys.add(IOC_PARTIAL_FILL);
      } else if (!remainder && filled.equals(place.quantityLots())) {
        keys.add(IOC_FULL_FILL);
      }
    } else if ("FOK".equals(place.executionPolicy())) {
      if (rejected(outcome, "FOK_NOT_FILLABLE")) {
        keys.add(FOK_INSUFFICIENT);
        if (outsideLimitWouldChangeDecision(place, before)) {
          keys.add(FOK_OUTSIDE_LIMIT_EXCLUDED);
        }
      } else if (accepted(outcome)) {
        keys.add(FOK_ACCEPTED);
        if (inLimitDepth(place, before).equals(place.quantityLots())) {
          keys.add(FOK_EXACT);
        }
        Set<BigInteger> prices = new HashSet<>();
        outcome.events().stream()
            .filter(SemanticEvent.Trade.class::isInstance)
            .map(SemanticEvent.Trade.class::cast)
            .map(SemanticEvent.Trade::priceTicks)
            .forEach(prices::add);
        if (prices.size() >= 2) {
          keys.add(FOK_MULTI_LEVEL);
        }
      }
    } else if ("POST_ONLY".equals(place.executionPolicy())) {
      List<SemanticBook.PriceLevel> opposite =
          "BUY".equals(place.side()) ? before.asks() : before.bids();
      if (before.bids().isEmpty() && before.asks().isEmpty() && accepted(outcome)) {
        keys.add(POST_ONLY_EMPTY_BOOK);
      } else if (!opposite.isEmpty()) {
        int comparison = place.priceTicks().compareTo(opposite.getFirst().priceTicks());
        boolean touch = comparison == 0;
        boolean crosses = "BUY".equals(place.side()) ? comparison > 0 : comparison < 0;
        if (touch && rejected(outcome, "POST_ONLY_WOULD_TAKE")) {
          keys.add(POST_ONLY_TOUCH);
        } else if (crosses && rejected(outcome, "POST_ONLY_WOULD_TAKE")) {
          keys.add(POST_ONLY_CROSS);
        } else if (!touch && !crosses && accepted(outcome)) {
          keys.add(POST_ONLY_NON_CROSSING);
        }
      }
    }
  }

  private static boolean outsideLimitWouldChangeDecision(
      ReferenceCommand.Place place, SemanticBook before) {
    List<SemanticBook.PriceLevel> opposite =
        "BUY".equals(place.side()) ? before.asks() : before.bids();
    BigInteger inLimit = BigInteger.ZERO;
    BigInteger all = BigInteger.ZERO;
    for (SemanticBook.PriceLevel level : opposite) {
      BigInteger levelQuantity =
          level.orders().stream()
              .map(SemanticBook.RestingOrder::remainingQuantityLots)
              .reduce(BigInteger.ZERO, BigInteger::add);
      all = all.add(levelQuantity);
      boolean executable =
          "BUY".equals(place.side())
              ? level.priceTicks().compareTo(place.priceTicks()) <= 0
              : level.priceTicks().compareTo(place.priceTicks()) >= 0;
      if (executable) {
        inLimit = inLimit.add(levelQuantity);
      }
    }
    return inLimit.compareTo(place.quantityLots()) < 0 && all.compareTo(place.quantityLots()) >= 0;
  }

  private static BigInteger inLimitDepth(ReferenceCommand.Place place, SemanticBook before) {
    List<SemanticBook.PriceLevel> opposite =
        "BUY".equals(place.side()) ? before.asks() : before.bids();
    BigInteger result = BigInteger.ZERO;
    for (SemanticBook.PriceLevel level : opposite) {
      boolean executable =
          "BUY".equals(place.side())
              ? level.priceTicks().compareTo(place.priceTicks()) <= 0
              : level.priceTicks().compareTo(place.priceTicks()) >= 0;
      if (executable) {
        result =
            result.add(
                level.orders().stream()
                    .map(SemanticBook.RestingOrder::remainingQuantityLots)
                    .reduce(BigInteger.ZERO, BigInteger::add));
      }
    }
    return result;
  }

  private static boolean baseValid(ReferenceCommand.Place place) {
    return "BTC-USDT".equals(place.instrumentId())
        && positiveLong(place.orderId())
        && SIDES.contains(place.side())
        && positiveLong(place.priceTicks())
        && positiveLong(place.quantityLots());
  }

  private static boolean positiveLong(BigInteger value) {
    return value.signum() > 0 && value.compareTo(MAXIMUM) <= 0;
  }

  private static boolean accepted(SemanticOutcome outcome) {
    return outcome.events().getFirst() instanceof SemanticEvent.Accepted;
  }

  private static boolean rejected(SemanticOutcome outcome, String code) {
    return outcome.events().getFirst() instanceof SemanticEvent.PlaceRejected rejected
        && code.equals(rejected.code());
  }

  private static boolean hasRemainder(SemanticOutcome outcome) {
    return outcome.events().stream().anyMatch(SemanticEvent.RemainderCanceled.class::isInstance);
  }

  private static BigInteger traded(SemanticOutcome outcome) {
    return outcome.events().stream()
        .filter(SemanticEvent.Trade.class::isInstance)
        .map(SemanticEvent.Trade.class::cast)
        .map(SemanticEvent.Trade::quantityLots)
        .reduce(BigInteger.ZERO, BigInteger::add);
  }

  private static List<String> requiredKeys() {
    return List.of(
        IOC_ZERO_FILL,
        IOC_PARTIAL_FILL,
        IOC_FULL_FILL,
        FOK_INSUFFICIENT,
        FOK_ACCEPTED,
        FOK_EXACT,
        FOK_MULTI_LEVEL,
        FOK_OUTSIDE_LIMIT_EXCLUDED,
        POST_ONLY_EMPTY_BOOK,
        POST_ONLY_NON_CROSSING,
        POST_ONLY_TOUCH,
        POST_ONLY_CROSS,
        BASE_VALID_UNKNOWN,
        BASE_VALID_UNUSED_ID_UNKNOWN,
        REJECTED_ID_LATER_REUSED);
  }

  private static String sidePolicy(String side, String policy) {
    return "SIDE_POLICY_" + side + '_' + policy;
  }

  record Witness(int historyIndex, int commandIndex, String seedHex, String laneId) {}

  record Result(
      Map<String, Integer> counts,
      Map<String, Witness> firstWitnesses,
      Set<Occurrence> occurrences) {
    Result {
      counts = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(counts));
      firstWitnesses = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(firstWitnesses));
      occurrences = Set.copyOf(occurrences);
    }

    void assertRequired() {
      List<String> missing = new ArrayList<>();
      for (Map.Entry<String, Integer> entry : counts.entrySet()) {
        if (entry.getValue() <= 0) {
          missing.add(entry.getKey());
        }
      }
      if (!missing.isEmpty()) {
        throw new IllegalStateException("M04 generated semantic coverage missing: " + missing);
      }
    }

    boolean observedAt(String key, int historyIndex, int commandIndex) {
      return occurrences.contains(new Occurrence(key, historyIndex, commandIndex));
    }
  }

  private record Occurrence(String key, int historyIndex, int commandIndex) {}
}
